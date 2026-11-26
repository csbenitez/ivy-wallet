package com.ivy.googlesheets.remote

import com.ivy.googlesheets.auth.GoogleSheetsAuth
import com.ivy.googlesheets.sync.SheetTable
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google Sheets API v4 over plain REST, authorized with the OAuth access token
 * from [GoogleSheetsAuth]. Uses its own HttpClient without body logging,
 * so neither tokens nor the user's financial data end up in logs.
 */
@Singleton
class GoogleSheetsRestApi @Inject constructor(
    private val auth: GoogleSheetsAuth,
) : SheetsApi {
    private val client by lazy { HttpClient(OkHttp) { expectSuccess = false } }
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun spreadsheetInfo(spreadsheetId: String): SpreadsheetInfo {
        val body = call { authorize ->
            client.get("$BASE/${spreadsheetId.encodeURLPathPart()}") {
                parameter("fields", "properties.title,sheets.properties.title")
                authorize(this)
            }
        }
        val root = json.parseToJsonElement(body).jsonObject
        val title = root["properties"]?.jsonObject?.get("title")?.jsonPrimitive?.content.orEmpty()
        val tabs = root["sheets"]?.jsonArray.orEmpty().mapNotNull { sheet ->
            sheet.jsonObject["properties"]?.jsonObject?.get("title")?.jsonPrimitive?.content
        }
        return SpreadsheetInfo(title = title, tabs = tabs)
    }

    override suspend fun readTable(spreadsheetId: String, tab: String): SheetTable {
        val body = call { authorize ->
            client.get("$BASE/${spreadsheetId.encodeURLPathPart()}/values/${a1Tab(tab).encodeURLPathPart()}") {
                parameter("valueRenderOption", "UNFORMATTED_VALUE")
                parameter("dateTimeRenderOption", "FORMATTED_STRING")
                parameter("majorDimension", "ROWS")
                authorize(this)
            }
        }
        val values = json.parseToJsonElement(body).jsonObject["values"]?.jsonArray.orEmpty()
        return SheetTable.fromRawValues(
            values.map { row -> row.jsonArray.map(::cellValue) }
        )
    }

    override suspend fun appendRows(spreadsheetId: String, tab: String, rows: List<List<Any>>) {
        if (rows.isEmpty()) return
        val payload = buildJsonObject {
            put("majorDimension", "ROWS")
            put("values", JsonArray(rows.map { row -> JsonArray(row.map(::toJson)) }))
        }
        call { authorize ->
            client.post(
                "$BASE/${spreadsheetId.encodeURLPathPart()}/values/${a1Tab(tab).encodeURLPathPart()}:append"
            ) {
                parameter("valueInputOption", "USER_ENTERED")
                parameter("insertDataOption", "INSERT_ROWS")
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
                authorize(this)
            }
        }
    }

    private suspend fun call(
        request: suspend (authorize: HttpRequestBuilder.() -> Unit) -> HttpResponse
    ): String {
        var retriedAuth = false
        while (true) {
            val token = auth.accessToken()
            val response = try {
                request { bearerAuth(token) }
            } catch (e: IOException) {
                throw SheetsException.Network(e)
            }
            val body = response.bodyAsText()
            when {
                response.status.isSuccess() -> return body
                response.status == HttpStatusCode.Unauthorized && !retriedAuth -> {
                    auth.invalidate(token)
                    retriedAuth = true
                }

                else -> throw errorFor(response.status, body)
            }
        }
    }

    private fun errorFor(status: HttpStatusCode, body: String): SheetsException = when {
        status == HttpStatusCode.Unauthorized -> SheetsException.Unauthorized()
        status == HttpStatusCode.Forbidden -> SheetsException.PermissionDenied()
        status == HttpStatusCode.NotFound -> SheetsException.NotFound()
        status == HttpStatusCode.BadRequest && body.contains("Unable to parse range") ->
            SheetsException.NotFound()

        else -> SheetsException.Api(status.value, body.take(ERROR_BODY_LIMIT))
    }

    private fun cellValue(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.content
            else -> element.content.toBigDecimalOrNull() ?: element.content
        }

        is JsonObject, is JsonArray -> element.toString()
    }

    private fun toJson(value: Any): JsonElement = when (value) {
        is BigDecimal -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    companion object {
        private const val BASE = "https://sheets.googleapis.com/v4/spreadsheets"
        private const val ERROR_BODY_LIMIT = 300

        /** A1 notation for a whole tab: 'My tab' (single quotes escaped by doubling). */
        fun a1Tab(tab: String): String = "'" + tab.replace("'", "''") + "'"
    }
}

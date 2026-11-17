package com.ivy.plugins.gdrive

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal Google Drive REST v3 client for files in the app data folder,
 * a hidden folder only Ivy Wallet can read (scope `drive.appdata`).
 */
@Singleton
class GoogleDriveApi @Inject constructor(
    private val json: Json,
) {
    // Own client (instead of the shared one) so that access tokens
    // and wallet data never end up in the HTTP logs.
    private val httpClient by lazy {
        HttpClient {
            install(ContentNegotiation) {
                json(json)
            }
        }
    }

    suspend fun findFile(token: String, name: String): DriveFile? {
        val response = httpClient.get("$DRIVE_URL/files") {
            bearerAuth(token)
            parameter("spaces", APP_DATA_FOLDER)
            parameter("q", "name = '$name' and trashed = false")
            parameter("fields", "files($FILE_FIELDS)")
            parameter("orderBy", "modifiedTime desc")
            parameter("pageSize", 1)
        }
        return response.orThrow().body<DriveFileList>().files.firstOrNull()
    }

    suspend fun download(token: String, fileId: String): String =
        httpClient.get("$DRIVE_URL/files/$fileId") {
            bearerAuth(token)
            parameter("alt", "media")
        }.orThrow().bodyAsText()

    suspend fun create(token: String, name: String, content: String): DriveFile {
        val boundary = "ivy-${UUID.randomUUID()}"
        val metadata = json.encodeToString(
            DriveFileMetadata(name = name, parents = listOf(APP_DATA_FOLDER))
        )
        val body = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata).append("\r\n")
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(content).append("\r\n")
            append("--$boundary--")
        }
        return httpClient.post("$UPLOAD_URL/files") {
            bearerAuth(token)
            parameter("uploadType", "multipart")
            parameter("fields", FILE_FIELDS)
            setBody(
                TextContent(
                    text = body,
                    contentType = ContentType.parse("multipart/related; boundary=$boundary")
                )
            )
        }.orThrow().body()
    }

    suspend fun update(token: String, fileId: String, content: String): DriveFile =
        httpClient.patch("$UPLOAD_URL/files/$fileId") {
            bearerAuth(token)
            parameter("uploadType", "media")
            parameter("fields", FILE_FIELDS)
            setBody(TextContent(text = content, contentType = ContentType.Application.Json))
        }.orThrow().body()

    /** The email of the linked Google account, if Google shares it. */
    suspend fun userEmail(token: String): String? = httpClient.get("$DRIVE_URL/about") {
        bearerAuth(token)
        parameter("fields", "user(emailAddress)")
    }.orThrow().body<DriveAbout>().user.emailAddress

    suspend fun revoke(token: String) {
        httpClient.post("https://oauth2.googleapis.com/revoke") {
            parameter("token", token)
        }
    }

    private suspend fun HttpResponse.orThrow(): HttpResponse {
        if (!status.isSuccess()) {
            throw GoogleDriveException(status.value, bodyAsText())
        }
        return this
    }

    companion object {
        private const val DRIVE_URL = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3"
        private const val APP_DATA_FOLDER = "appDataFolder"
        private const val FILE_FIELDS = "id,version,modifiedTime"
    }
}

class GoogleDriveException(
    val httpStatus: Int,
    body: String,
) : Exception("Google Drive error $httpStatus: ${body.take(MAX_ERROR_LENGTH)}") {
    private companion object {
        const val MAX_ERROR_LENGTH = 300
    }
}

@Serializable
data class DriveFile(
    @SerialName("id")
    val id: String,
    @SerialName("version")
    val version: String,
    @SerialName("modifiedTime")
    val modifiedTime: String,
)

@Serializable
private data class DriveFileList(
    @SerialName("files")
    val files: List<DriveFile>,
)

@Serializable
private data class DriveFileMetadata(
    @SerialName("name")
    val name: String,
    @SerialName("parents")
    val parents: List<String>,
)

@Serializable
private data class DriveAbout(
    @SerialName("user")
    val user: DriveUser,
)

@Serializable
private data class DriveUser(
    @SerialName("emailAddress")
    val emailAddress: String,
)

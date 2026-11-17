package com.ivy.googlesheets.auth

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.ivy.googlesheets.remote.SheetsException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OAuth for the Google Sheets API via Google Identity Services' AuthorizationClient.
 *
 * Uses the non-sensitive `drive.file` scope: the app only gets access to the spreadsheets
 * the user picks in the Google Picker (or files the app creates), so no Google
 * verification is needed. The Sheets API works on those files as usual.
 *
 * No client id or secret lives in the app: Google matches the app by package name + signing
 * SHA-1 against an "Android" OAuth client in the developer's Google Cloud project.
 * The first call returns a consent [android.app.PendingIntent] (surfaced as
 * [SheetsException.NeedsConsent]); afterwards tokens are issued silently.
 */
@Singleton
class GoogleSheetsAuth @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client get() = Identity.getAuthorizationClient(context)

    private val request: AuthorizationRequest
        get() = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE_DRIVE_FILE)))
            .build()

    /** A fresh access token. Play services caches and refreshes it for us. */
    @Suppress("ThrowsCount")
    suspend fun accessToken(): String {
        val result = try {
            client.authorize(request).await()
        } catch (e: ApiException) {
            throw SheetsException.Api(e.statusCode, e.message.orEmpty())
        }
        val pendingIntent = result.pendingIntent
        if (result.hasResolution() && pendingIntent != null) {
            throw SheetsException.NeedsConsent(pendingIntent)
        }
        return result.accessToken ?: throw SheetsException.Unauthorized()
    }

    /** Called with the result of the consent screen launched from [SheetsException.NeedsConsent]. */
    fun handleConsentResult(data: Intent?): Boolean = data != null && try {
        client.getAuthorizationResultFromIntent(data).accessToken != null
    } catch (e: ApiException) {
        false
    }

    /** Hook for retrying after a 401; tokens are re-requested on every call. */
    @Suppress("UnusedParameter")
    fun invalidate(token: String) = Unit

    companion object {
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    }
}

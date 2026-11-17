package com.ivy.plugins.gdrive

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject

/**
 * Gets OAuth access tokens for the Google Drive app data folder
 * using Google Identity Services' AuthorizationClient.
 *
 * Requires an Android OAuth client (package name + signing SHA-1)
 * registered in a Google Cloud project with the Drive API enabled.
 */
class GoogleDriveAuth @Inject constructor(
    @ApplicationContext
    private val context: Context,
) {
    private val client by lazy { Identity.getAuthorizationClient(context) }

    private val request: AuthorizationRequest
        get() = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .build()

    /**
     * May return a result that [AuthorizationResult.hasResolution], in which
     * case its pending intent must be launched to ask the user for consent.
     */
    suspend fun authorize(): AuthorizationResult = client.authorize(request).await()

    fun resultFromIntent(data: Intent?): AuthorizationResult =
        client.getAuthorizationResultFromIntent(data)

    /** A token without user interaction, or null if consent is needed. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun silentAccessToken(): String? = try {
        authorize().takeUnless { it.hasResolution() }?.accessToken
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Google Drive silent authorization failed")
        null
    }

    companion object {
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }
}

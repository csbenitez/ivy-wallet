package com.ivy.googlesheets.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ivy.googlesheets.picker.GooglePickerPage

/**
 * Shows the Google Picker in a full-screen WebView. Picking a spreadsheet here is what
 * grants the app `drive.file` access to it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GooglePickerDialog(
    html: String,
    onPick: (id: String, name: String) -> Unit,
    onCancel: () -> Unit,
    onError: (String) -> Unit,
) {
    val picked = rememberUpdatedState(onPick)
    val cancel = rememberUpdatedState(onCancel)
    val error = rememberUpdatedState(onError)
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    val webView = this
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onPicked(id: String, name: String) {
                                webView.post { picked.value(id, name) }
                            }

                            @JavascriptInterface
                            fun onCancel() {
                                webView.post { cancel.value() }
                            }

                            @JavascriptInterface
                            fun onError(message: String) {
                                webView.post { error.value(message) }
                            }
                        },
                        GooglePickerPage.JS_INTERFACE
                    )
                    loadDataWithBaseURL(GooglePickerPage.BASE_URL, html, "text/html", "utf-8", null)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}

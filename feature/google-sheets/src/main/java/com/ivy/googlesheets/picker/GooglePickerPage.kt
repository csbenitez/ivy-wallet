package com.ivy.googlesheets.picker

import kotlinx.serialization.json.JsonPrimitive

/**
 * Google Picker settings. With the `drive.file` scope, a spreadsheet picked here becomes
 * accessible to the app's OAuth client in the same Google Cloud project ([appId]).
 */
class GooglePickerConfig(
    /** Browser API key restricted to the Google Picker API. */
    val apiKey: String,
    /** The Google Cloud project *number* (not id) that owns the Android OAuth client. */
    val appId: String,
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() && appId.isNotBlank()
}

/**
 * The web page that hosts the Google Picker (there's no native Android picker).
 * It reports back through the `IvyPicker` JavaScript interface:
 * `onPicked(id, name)`, `onCancel()` and `onError(message)`.
 */
object GooglePickerPage {
    const val JS_INTERFACE = "IvyPicker"
    const val BASE_URL = "https://ivy-wallet.local/"

    fun html(config: GooglePickerConfig, accessToken: String, title: String): String {
        fun js(value: String): String = JsonPrimitive(value).toString()
        return """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>html, body { margin: 0; height: 100%; background: #fff; }</style>
<script>
function fail(message) { $JS_INTERFACE.onError(String(message)); }
function start() {
  if (typeof gapi === 'undefined') { fail('Google API script did not load'); return; }
  gapi.load('picker', { callback: build, onerror: function () { fail('Picker did not load'); } });
}
function build() {
  try {
    var view = new google.picker.DocsView(google.picker.ViewId.SPREADSHEETS)
      .setMode(google.picker.DocsViewMode.LIST)
      .setIncludeFolders(true);
    var picker = new google.picker.PickerBuilder()
      .setOAuthToken(${js(accessToken)})
      .setDeveloperKey(${js(config.apiKey)})
      .setAppId(${js(config.appId)})
      .setTitle(${js(title)})
      .addView(view)
      .enableFeature(google.picker.Feature.NAV_HIDDEN)
      .setSize(window.innerWidth, window.innerHeight)
      .setCallback(onPicked)
      .build();
    picker.setVisible(true);
  } catch (e) { fail(e); }
}
function onPicked(data) {
  var action = data[google.picker.Response.ACTION];
  if (action === google.picker.Action.PICKED) {
    var doc = data[google.picker.Response.DOCUMENTS][0];
    $JS_INTERFACE.onPicked(doc[google.picker.Document.ID], doc[google.picker.Document.NAME] || '');
  } else if (action === google.picker.Action.CANCEL) {
    $JS_INTERFACE.onCancel();
  }
}
</script>
<script src="https://apis.google.com/js/api.js" onload="start()" onerror="fail('Network error')"></script>
</head>
<body></body>
</html>
""".trimIndent()
    }
}

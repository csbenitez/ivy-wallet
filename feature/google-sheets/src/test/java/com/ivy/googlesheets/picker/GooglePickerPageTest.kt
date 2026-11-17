package com.ivy.googlesheets.picker

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

class GooglePickerPageTest {
    @Test
    fun `embeds config and token as escaped JS strings`() {
        val html = GooglePickerPage.html(
            config = GooglePickerConfig(apiKey = "key-123", appId = "987654321"),
            accessToken = "tok\"en</script>",
            title = "Google Sheets",
        )
        html shouldContain ".setDeveloperKey(\"key-123\")"
        html shouldContain ".setAppId(\"987654321\")"
        html shouldContain ".setOAuthToken(\"tok\\\"en"
        html shouldContain "google.picker.ViewId.SPREADSHEETS"
        html shouldContain "${GooglePickerPage.JS_INTERFACE}.onPicked("
        html shouldNotContain "\$JS_INTERFACE"
    }

    @Test
    fun `config is incomplete without key or project number`() {
        GooglePickerConfig(apiKey = "", appId = "1").isConfigured shouldBe false
        GooglePickerConfig(apiKey = "k", appId = "").isConfigured shouldBe false
        GooglePickerConfig(apiKey = "k", appId = "1").isConfigured shouldBe true
    }
}

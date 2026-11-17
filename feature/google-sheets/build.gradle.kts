plugins {
    id("ivy.feature")
}

android {
    namespace = "com.ivy.googlesheets"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // Google Picker config (see README.md). Set them in ~/.gradle/gradle.properties
        // or as environment variables; without them the "pick a sheet" button explains what's missing.
        buildConfigField("String", "GOOGLE_PICKER_API_KEY", "\"${pickerProperty("googlePickerApiKey")}\"")
        buildConfigField("String", "GOOGLE_CLOUD_PROJECT_NUMBER", "\"${pickerProperty("googleCloudProjectNumber")}\"")
    }
}

fun pickerProperty(name: String): String =
    providers.gradleProperty("ivy.$name").orNull
        ?: providers.environmentVariable(
            name.replace(Regex("([A-Z])"), "_$1").uppercase()
        ).orNull
        ?: ""

dependencies {
    implementation(projects.feature.importData)
    implementation(projects.shared.base)
    implementation(projects.shared.data.core)
    implementation(projects.shared.domain)
    implementation(projects.shared.ui.core)
    implementation(projects.shared.ui.navigation)

    implementation(libs.bundles.ktor)
    implementation(libs.bundles.opencsv)
    implementation(libs.google.play.services.auth)
}

plugins {
    id("ivy.feature")
}

android {
    namespace = "com.ivy.plugins.gdrive"
}

dependencies {
    implementation(projects.shared.base)
    implementation(projects.shared.data.core)
    implementation(projects.shared.ui.core)
    implementation(projects.shared.ui.navigation)
    implementation(projects.feature.plugins.api)

    implementation(libs.bundles.ktor)
    implementation(libs.google.play.services.auth)
}

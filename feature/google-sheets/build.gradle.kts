plugins {
    id("ivy.feature")
}

android {
    namespace = "com.ivy.googlesheets"
}

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

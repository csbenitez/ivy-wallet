plugins {
    id("ivy.feature")
}

android {
    namespace = "com.ivy.sharedwallet"
}

dependencies {
    implementation(projects.shared.base)
    implementation(projects.shared.data.core)
    implementation(projects.shared.ui.core)
    implementation(projects.shared.ui.navigation)

    implementation(libs.bundles.room)
    implementation(libs.androidx.work)

    testImplementation(libs.bundles.testing)
}

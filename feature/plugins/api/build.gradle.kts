plugins {
    id("ivy.feature")
}

android {
    namespace = "com.ivy.plugins.api"
}

dependencies {
    implementation(projects.shared.base)
    implementation(projects.shared.data.core)
}

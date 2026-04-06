plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.grinwich.sdk.wiring.g"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // PUBLIC -- the app gets these transitively
    api(project(":sdk:api"))

    // PRIVATE -- factory functions from each feature-impl
    implementation(project(":feature-core-impl"))
    implementation(project(":feature-enc-impl"))
    implementation(project(":feature-auth-impl"))
    implementation(project(":feature-stor-impl"))
    implementation(project(":feature-ana-impl"))
    implementation(project(":feature-syn-impl"))
    implementation(project(":feature-observability-impl")) // AndroidSdkLogger (default logger)
}

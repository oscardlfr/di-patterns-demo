plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.grinwich.sdk.api"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Umbrella: re-exports core-api + observability + all feature APIs
    api(project(":feature-core-api"))
    api(project(":observability-api"))
    api(project(":feature-enc-api"))
    api(project(":feature-auth-api"))
    api(project(":feature-stor-api"))
    api(project(":feature-ana-api"))
    api(project(":feature-syn-api"))
}

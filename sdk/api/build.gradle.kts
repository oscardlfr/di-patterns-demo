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
    api(project(":features:feature-core-api"))
    api(project(":features:observability-api"))
    api(project(":features:feature-enc-api"))
    api(project(":features:feature-auth-api"))
    api(project(":features:feature-stor-api"))
    api(project(":features:feature-ana-api"))
    api(project(":features:feature-syn-api"))
}

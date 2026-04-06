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
    // Umbrella: re-exports core-api + all feature APIs so existing consumers need no changes
    api(project(":sdk:core-api"))
    api(project(":sdk:feature-enc-api"))
    api(project(":sdk:feature-auth-api"))
    api(project(":sdk:feature-stor-api"))
    api(project(":sdk:feature-ana-api"))
    api(project(":sdk:feature-syn-api"))
}

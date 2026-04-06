plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.grinwich.sdk.contracts"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Provision interfaces reference these types
    api(project(":feature-core-api"))      // SdkConfig
    api(project(":observability-api"))      // SdkLogger
    api(project(":feature-enc-api"))        // EncryptionService, HashService
    api(project(":feature-auth-api"))       // AuthService
    api(project(":feature-stor-api"))       // SecureStorageService
    api(project(":feature-ana-api"))        // AnalyticsService
    api(project(":feature-syn-api"))        // SyncService
    // javax.inject for @Scope annotations
    implementation(libs.javax.inject)
}

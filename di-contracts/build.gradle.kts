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
    api(project(":features:feature-core-api"))      // SdkConfig
    api(project(":features:observability-api"))      // SdkLogger
    api(project(":features:feature-enc-api"))        // EncryptionService, HashService
    api(project(":features:feature-auth-api"))       // AuthService
    api(project(":features:feature-stor-api"))       // SecureStorageService
    api(project(":features:feature-ana-api"))        // AnalyticsService
    api(project(":features:feature-syn-api"))        // SyncService
    // javax.inject for @Scope annotations
    implementation(libs.javax.inject)
}

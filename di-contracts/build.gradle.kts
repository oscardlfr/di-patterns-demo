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
    // Provision interfaces reference these types internally.
    // implementation() — di-contracts does NOT transitively expose feature-api types.
    // Each feature-impl declares its own explicit dependencies on the apis it uses.
    implementation(project(":features:feature-core-api"))      // SdkConfig
    implementation(project(":features:observability-api"))      // SdkLogger
    implementation(project(":features:feature-enc-api"))        // EncryptionApi, HashApi
    implementation(project(":features:feature-auth-api"))       // AuthApi
    implementation(project(":features:feature-stor-api"))       // StorageApi
    implementation(project(":features:feature-ana-api"))        // AnalyticsApi
    implementation(project(":features:feature-syn-api"))        // SyncApi
    // javax.inject for @Scope annotations
    implementation(libs.javax.inject)
}

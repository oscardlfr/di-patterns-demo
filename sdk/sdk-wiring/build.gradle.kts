plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.grinwich.sdk.wiring"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // PUBLIC — the app gets these transitively
    api(project(":sdk:api"))                    // EncryptionService, AuthService, etc.

    // PRIVATE — the app NEVER sees these
    implementation(project(":di-contracts"))        // provision interfaces (used internally)
    implementation(project(":features:feature-core-impl"))   // DaggerCoreComponent
    implementation(project(":features:feature-enc-impl"))    // DaggerEncComponent
    implementation(project(":features:feature-auth-impl"))   // DaggerAuthComponent
    implementation(project(":features:feature-stor-impl"))   // DaggerStorComponent
    implementation(project(":features:feature-ana-impl"))    // DaggerAnaComponent
    implementation(project(":features:feature-syn-impl"))    // DaggerSynComponent
    implementation(project(":features:feature-observability-impl")) // AndroidSdkLogger (default logger)
}

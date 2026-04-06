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
    implementation(project(":sdk:di-contracts"))        // provision interfaces (used internally)
    implementation(project(":sdk:feature-core-impl"))   // DaggerCoreComponent
    implementation(project(":sdk:feature-enc-impl"))    // DaggerEncComponent
    implementation(project(":sdk:feature-auth-impl"))   // DaggerAuthComponent
    implementation(project(":sdk:feature-stor-impl"))   // DaggerStorComponent
    implementation(project(":sdk:feature-ana-impl"))    // DaggerAnaComponent
    implementation(project(":sdk:feature-syn-impl"))    // DaggerSynComponent
    implementation(project(":sdk:impl-common"))         // AndroidSdkLogger
}

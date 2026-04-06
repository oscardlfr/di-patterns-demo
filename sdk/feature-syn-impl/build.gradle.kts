plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.grinwich.sdk.feature.syn"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":sdk:feature-syn-contracts"))   // SynProvisions, SynScope
    api(project(":sdk:feature-core-contracts"))  // CoreProvisions
    api(project(":sdk:feature-enc-contracts"))   // EncProvisions (cross-feature)
    api(project(":sdk:feature-auth-contracts"))  // AuthProvisions (cross-feature)
    api(project(":sdk:feature-stor-contracts"))  // StorProvisions (cross-feature)
    implementation(project(":sdk:impl-common"))  // DefaultSyncService

    // Sync has the heaviest cross-deps: Core + Enc + Auth + Storage
    // ALL resolved via provision interfaces (contracts), NOT feature-impl modules.

    implementation(libs.dagger)
    ksp(libs.dagger.compiler)
    implementation(libs.javax.inject)
}

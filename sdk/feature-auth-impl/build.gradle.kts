plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.grinwich.sdk.feature.auth"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":sdk:feature-auth-contracts"))  // AuthProvisions, AuthScope
    api(project(":sdk:feature-core-contracts"))  // CoreProvisions (component dependency)
    api(project(":sdk:feature-enc-contracts"))   // EncProvisions (cross-feature contract)
    implementation(project(":sdk:impl-common"))  // DefaultAuthService

    // Cross-feature dependency via PROVISION INTERFACE:
    // This module depends on EncProvisions (contract),
    // NOT on :sdk:feature-enc-impl (implementation).

    implementation(libs.dagger)
    ksp(libs.dagger.compiler)
    implementation(libs.javax.inject)
}

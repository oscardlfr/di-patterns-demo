plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.grinwich.sdk.feature.enc"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":sdk:feature-enc-contracts"))   // EncProvisions, EncScope
    api(project(":sdk:feature-core-contracts"))  // CoreProvisions (component dependency)
    implementation(project(":sdk:impl-common"))  // DefaultEncryptionService, DefaultHashService

    // NOTE: does NOT depend on :sdk:feature-core-impl
    // It depends on CoreProvisions (contract), not CoreComponent (impl)

    implementation(libs.dagger)
    ksp(libs.dagger.compiler)
    implementation(libs.javax.inject)
}

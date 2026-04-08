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
    implementation(project(":di-contracts"))  // CoreProvisions, EncProvisions, Resolver
    implementation(project(":features:feature-enc-api"))  // EncryptionApi, HashApi
    implementation(project(":features:feature-core-api"))  // SdkConfig
    implementation(project(":features:observability-api"))  // SdkLogger

    implementation(libs.dagger)
    ksp(libs.dagger.compiler)
    implementation(libs.javax.inject)

    implementation(libs.kotlin.inject.runtime)
    ksp(libs.kotlin.inject.compiler)
}

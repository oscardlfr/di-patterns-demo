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
    implementation(project(":di-contracts"))  // All provision interfaces + SynScope + Resolver
    implementation(project(":features:feature-syn-api"))  // SyncApi, SyncResult
    implementation(project(":features:feature-enc-api"))  // EncryptionApi (cross-feature dep)
    implementation(project(":features:feature-auth-api"))  // AuthApi (cross-feature dep)
    implementation(project(":features:feature-stor-api"))  // StorageApi (cross-feature dep)
    implementation(project(":features:feature-core-api"))  // SdkConfig
    implementation(project(":features:observability-api"))  // SdkLogger

    implementation(libs.dagger)
    ksp(libs.dagger.compiler)
    implementation(libs.javax.inject)

    implementation(libs.kotlin.inject.runtime)
    ksp(libs.kotlin.inject.compiler)
}

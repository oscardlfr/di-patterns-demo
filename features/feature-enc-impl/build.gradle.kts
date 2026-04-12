plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.metro)
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
    implementation(libs.hilt.android)  // @InstallIn, SingletonComponent (Pattern Q)
    ksp(libs.dagger.compiler)
    implementation(libs.javax.inject)

    implementation(libs.kotlin.inject.runtime)
    ksp(libs.kotlin.inject.compiler)

    implementation(project(":di-contracts-koin"))  // KoinFeatureProvider, CreationTracker
    implementation(libs.koin.core)
    implementation(libs.sweet.spi.runtime)  // @ServiceProvider for sweet-spi KMP discovery
    ksp(libs.sweet.spi.processor)
    implementation(libs.kotlin.inject.anvil.runtime)  // @ContributesTo, @MergeComponent
    implementation(libs.kotlin.inject.anvil.runtime.optional)  // @SingleIn, AppScope (unused — using Metro's AppScope)
    ksp(libs.kotlin.inject.anvil.compiler)
}

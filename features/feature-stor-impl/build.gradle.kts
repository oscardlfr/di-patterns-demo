plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.metro)
}

android {
    namespace = "com.grinwich.sdk.feature.stor"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":di-contracts"))  // FeatureProvider, Flavor, Resolver
    implementation(project(":features:feature-stor-api"))  // StorageApi
    implementation(project(":features:feature-enc-api"))  // EncryptionApi, HashApi (cross-feature dep)
    implementation(project(":sdk:api"))  // SdkConfig (vive en sdk/api)
    implementation(project(":features:observability-api"))  // SdkLogger

    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)

    implementation(libs.dagger)
    implementation(libs.hilt.android)
    ksp(libs.dagger.compiler)
    implementation(libs.javax.inject)

    implementation(libs.kotlin.inject.runtime)
    ksp(libs.kotlin.inject.compiler)

    implementation(project(":di-contracts-koin"))  // KoinFeatureProvider, CreationTracker
    implementation(libs.koin.core)
    implementation(libs.sweet.spi.runtime)
    ksp(libs.sweet.spi.processor)
    implementation(libs.kotlin.inject.anvil.runtime)
    implementation(libs.kotlin.inject.anvil.runtime.optional)
    ksp(libs.kotlin.inject.anvil.compiler)
}

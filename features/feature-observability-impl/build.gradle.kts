plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.grinwich.sdk.feature.observability"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":di-contracts"))  // FeatureProvider, Flavor, Resolver
    implementation(project(":features:observability-api"))  // SdkLogger

    // Koin providers para L/M/N — así el wiring Koin descubre Observability
    // por ServiceLoader/sweet-spi y deja de necesitar `implementation` directa.
    implementation(project(":di-contracts-koin"))
    implementation(libs.koin.core)
    implementation(libs.sweet.spi.runtime)
    ksp(libs.sweet.spi.processor)
}

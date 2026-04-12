plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.grinwich.sdk.contracts.koin"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.koin.core)
    implementation(libs.sweet.spi.runtime)  // @Service annotation for Pattern N discovery
    ksp(libs.sweet.spi.processor)  // Generates service type registration for @Service
}

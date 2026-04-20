plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.grinwich.sdk.wiring.l"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // PUBLIC -- app sees interfaces transitively
    api(project(":sdk:api"))

    // Koin infrastructure
    implementation(project(":di-contracts-koin"))  // KoinFeatureProvider, CreationTracker
    runtimeOnly(project(":features:feature-observability-impl"))  // AndroidSdkLogger
    implementation(libs.koin.core)

    // RUNTIME ONLY -- ALL feature impls discovered via ServiceLoader
    runtimeOnly(project(":features:feature-enc-impl"))
    runtimeOnly(project(":features:feature-auth-impl"))
    runtimeOnly(project(":features:feature-stor-impl"))
    runtimeOnly(project(":features:feature-ana-impl"))
    runtimeOnly(project(":features:feature-syn-impl"))
}

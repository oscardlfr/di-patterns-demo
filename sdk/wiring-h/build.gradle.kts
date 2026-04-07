plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.grinwich.sdk.wiring.h"
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

    // WIRING infra -- FeatureProvider, Resolver
    implementation(project(":sdk:di-contracts"))

    // DEFAULT LOGGER -- only for the default parameter in init()
    implementation(project(":feature-observability-impl"))

    // RUNTIME ONLY -- feature impls discovered via ServiceLoader
    // App and this module CANNOT compile against these classes.
    // ServiceLoader finds FeatureProviders in META-INF/services at runtime.
    runtimeOnly(project(":feature-core-impl"))
    runtimeOnly(project(":feature-enc-impl"))
    runtimeOnly(project(":feature-auth-impl"))
    runtimeOnly(project(":feature-stor-impl"))
    runtimeOnly(project(":feature-ana-impl"))
    runtimeOnly(project(":feature-syn-impl"))
}

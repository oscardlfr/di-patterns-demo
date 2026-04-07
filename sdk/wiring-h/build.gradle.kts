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
    implementation(project(":di-contracts"))

    // RUNTIME ONLY -- ALL feature impls discovered via ServiceLoader
    // Zero compile-time coupling to any feature impl
    runtimeOnly(project(":features:feature-core-impl"))
    runtimeOnly(project(":features:feature-enc-impl"))
    runtimeOnly(project(":features:feature-auth-impl"))
    runtimeOnly(project(":features:feature-stor-impl"))
    runtimeOnly(project(":features:feature-ana-impl"))
    runtimeOnly(project(":features:feature-syn-impl"))
    runtimeOnly(project(":features:feature-observability-impl"))
}

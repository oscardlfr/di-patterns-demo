plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.grinwich.sdk.wiring.p2"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":sdk:api"))
    implementation(project(":di-contracts"))  // SdkScope, LazyCreationTracker
    implementation(project(":features:feature-observability-impl"))  // AndroidSdkLogger

    implementation(libs.kotlin.inject.runtime)
    ksp(libs.kotlin.inject.compiler)
    implementation(libs.kotlin.inject.anvil.runtime)
    implementation(libs.kotlin.inject.anvil.runtime.optional)
    ksp(libs.kotlin.inject.anvil.compiler)

    // Feature impls with @ContributesTo(SdkScope) bindings
    implementation(project(":features:feature-enc-impl"))
    implementation(project(":features:feature-auth-impl"))
    implementation(project(":features:feature-stor-impl"))
    implementation(project(":features:feature-ana-impl"))
    implementation(project(":features:feature-syn-impl"))
}

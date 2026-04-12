plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.grinwich.sdk.wiring.q2"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":sdk:api"))
    implementation(project(":di-contracts"))  // LazyCreationTracker
    implementation(project(":features:feature-observability-impl"))  // AndroidSdkLogger

    // Dagger for @Component generation
    implementation(libs.dagger)
    ksp(libs.dagger.compiler)
    implementation(libs.javax.inject)

    // Feature impls with Hilt-style @Module @InstallIn
    implementation(project(":features:feature-enc-impl"))
    implementation(project(":features:feature-auth-impl"))
    implementation(project(":features:feature-stor-impl"))
    implementation(project(":features:feature-ana-impl"))
    implementation(project(":features:feature-syn-impl"))
}

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.grinwich.sdk.wiring.g"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // PUBLIC -- the app gets these transitively
    api(project(":sdk:api"))

    // DI infrastructure -- provision interfaces, factory functions
    implementation(project(":di-contracts"))

    // PRIVATE -- factory functions from each feature-impl
    implementation(project(":features:feature-core-impl"))
    implementation(project(":features:feature-enc-impl"))
    implementation(project(":features:feature-auth-impl"))
    implementation(project(":features:feature-stor-impl"))
    implementation(project(":features:feature-ana-impl"))
    implementation(project(":features:feature-syn-impl"))
    implementation(project(":features:feature-observability-impl")) // AndroidSdkLogger (default logger)
}

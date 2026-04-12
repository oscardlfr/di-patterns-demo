plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.metro)
}

android {
    namespace = "com.grinwich.sdk.wiring.o"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":sdk:api"))
    implementation(project(":features:feature-observability-impl"))  // AndroidSdkLogger

    // Feature impls with Metro @ContributesTo bindings
    implementation(project(":features:feature-enc-impl"))
    implementation(project(":features:feature-auth-impl"))
    implementation(project(":features:feature-stor-impl"))
    implementation(project(":features:feature-ana-impl"))
    implementation(project(":features:feature-syn-impl"))
}

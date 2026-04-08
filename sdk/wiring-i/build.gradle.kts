plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.grinwich.sdk.wiring.i"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":sdk:api"))
    implementation(project(":di-contracts"))

    // Pattern I: zero Dagger dependency — only runtimeOnly on feature impls
    runtimeOnly(project(":features:feature-core-impl"))
    runtimeOnly(project(":features:feature-enc-impl"))
    runtimeOnly(project(":features:feature-auth-impl"))
    runtimeOnly(project(":features:feature-stor-impl"))
    runtimeOnly(project(":features:feature-ana-impl"))
    runtimeOnly(project(":features:feature-syn-impl"))
    runtimeOnly(project(":features:feature-observability-impl"))
}

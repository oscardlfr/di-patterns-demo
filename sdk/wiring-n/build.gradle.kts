plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.grinwich.sdk.wiring.n"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":sdk:api"))
    implementation(project(":di-contracts-koin"))
    runtimeOnly(project(":features:feature-observability-impl"))
    implementation(libs.koin.core)
    implementation(libs.sweet.spi.runtime)

    runtimeOnly(project(":features:feature-enc-impl"))
    runtimeOnly(project(":features:feature-auth-impl"))
    runtimeOnly(project(":features:feature-stor-impl"))
    runtimeOnly(project(":features:feature-ana-impl"))
    runtimeOnly(project(":features:feature-syn-impl"))
}

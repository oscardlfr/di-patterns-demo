plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.grinwich.sdk.wiring.e2"
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

    // PRIVATE -- the app NEVER sees these
    implementation(project(":sdk:di-contracts"))
    implementation(project(":sdk:feature-core-impl"))
    implementation(project(":sdk:feature-enc-impl"))
    implementation(project(":sdk:feature-auth-impl"))
    implementation(project(":sdk:feature-stor-impl"))
    implementation(project(":sdk:feature-ana-impl"))
    implementation(project(":sdk:feature-syn-impl"))
    implementation(project(":sdk:impl-common"))
}

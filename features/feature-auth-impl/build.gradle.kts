plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.grinwich.sdk.feature.auth"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":di-contracts"))  // CoreProvisions, EncProvisions, AuthProvisions, AuthScope

    // DefaultAuthService lives HERE (internal)
    // Cross-feature dep on EncProvisions (contract), NOT :feature-enc-impl

    implementation(libs.dagger)
    ksp(libs.dagger.compiler)
    implementation(libs.javax.inject)
}

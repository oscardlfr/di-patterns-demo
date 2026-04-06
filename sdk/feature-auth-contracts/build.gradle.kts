plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.grinwich.sdk.feature.auth.contracts"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":sdk:feature-auth-api"))
    implementation(libs.javax.inject)
}

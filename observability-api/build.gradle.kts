plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.grinwich.sdk.observability.api"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Zero dependencies — SdkLogger is a foundational cross-cutting concern

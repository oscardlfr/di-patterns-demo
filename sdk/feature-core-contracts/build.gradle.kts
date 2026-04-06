plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.grinwich.sdk.feature.core.contracts"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Only core-api — NOT the sdk/api umbrella. True isolation.
    api(project(":sdk:core-api"))
}

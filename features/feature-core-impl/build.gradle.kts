plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.grinwich.sdk.feature.core"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":di-contracts"))  // FeatureProvider, Flavor, Resolver
    implementation(project(":sdk:api"))  // SdkConfig (vive en sdk/api)
}

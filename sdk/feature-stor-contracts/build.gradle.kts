plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.grinwich.sdk.feature.stor.contracts"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":sdk:feature-stor-api"))
    implementation(libs.javax.inject)
}

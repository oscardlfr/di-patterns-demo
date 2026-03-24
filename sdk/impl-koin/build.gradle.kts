plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.grinwich.sdk.impl"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":sdk:impl-common"))
    implementation(libs.koin.core)
}

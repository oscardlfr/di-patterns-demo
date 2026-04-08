plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.grinwich.sample.multimodule"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.grinwich.sample.multimodule"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true }
}

dependencies {
    // The app ONLY depends on wiring-h (Pattern H — auto-discovery).
    // wiring-h uses api(:sdk:api) → app gets interfaces transitively.
    // wiring-h uses implementation(:feature-xxx-impl) → app NEVER sees Components.
    implementation(project(":sdk:wiring-h"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    // Dagger 2 — demonstrates how a consumer app integrates the SDK with its own DI
    implementation(libs.dagger)
    ksp(libs.dagger.compiler)
    implementation(libs.javax.inject)
}

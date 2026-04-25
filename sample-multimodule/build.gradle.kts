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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true }

    // R8 release build is the contract that validates the ServiceLoader
    // keep rules survive shrinking + obfuscation. The integration smoke
    // test under src/androidTest must run against this configuration.
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Re-sign with the debug key so the release APK can be installed
            // for instrumented tests without a custom signing config.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
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

    // Instrumented integration test — validates that the release APK
    // (with R8 applied) still resolves every API the app declares.
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.junit)
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.grinwich.benchmark"
    compileSdk = 36
    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        // Suppress benchmark environment checks for validation.
        // For production numbers: use physical device, plugged in, screen off.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR,LOW-BATTERY"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testBuildType = "release"
    buildTypes {
        release {
            isDefault = true
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":sdk:api"))
    implementation(project(":sdk:impl-common"))
    implementation(project(":sdk:impl-koin"))
    implementation(project(":sdk:impl-dagger-b"))
    implementation(project(":sdk:impl-dagger-c"))
    implementation(project(":sdk:impl-dagger-d"))
    implementation(libs.dagger)
    ksp(libs.dagger.compiler)
    implementation(libs.javax.inject)
    implementation(libs.koin.core)

    androidTestImplementation(libs.benchmark.junit4)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}

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
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR,LOW-BATTERY,ACTIVITY-MISSING"
        testInstrumentationRunnerArguments["disableAnalytics"] = "true"
        // On emulator, disable method tracing to save disk space (~50% less):
        //   ./gradlew :benchmark:connectedReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.profiling.mode=None
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
    // Monolithic SDK facades
    implementation(project(":sdk:impl-dagger-b"))
    implementation(project(":sdk:impl-dagger-c"))
    implementation(project(":sdk:impl-koin"))

    // Multi-module SDK facades
    implementation(project(":sdk:sdk-wiring"))
    implementation(project(":sdk:wiring-e"))
    implementation(project(":sdk:wiring-e2"))
    implementation(project(":sdk:wiring-g"))
    implementation(project(":sdk:wiring-h"))
    implementation(project(":sdk:wiring-i"))
    implementation(project(":sdk:wiring-j"))
    implementation(project(":sdk:wiring-k"))
    implementation(project(":sdk:wiring-l"))
    implementation(project(":sdk:wiring-m"))
    implementation(project(":sdk:wiring-n"))
    implementation(project(":sdk:wiring-o"))
    implementation(project(":sdk:wiring-p"))
    implementation(project(":sdk:wiring-q"))
    implementation(project(":sdk:wiring-o2"))
    implementation(project(":sdk:wiring-p2"))
    implementation(project(":sdk:wiring-q2"))

    // DI infrastructure — needed for ScaleBenchmark (Resolver, AutoProvisionRegistry)
    implementation(project(":di-contracts"))

    // Hybrid bridge — app-specific @Component (per-app, not per-SDK)
    implementation(project(":sdk:api"))
    implementation(libs.dagger)
    ksp(libs.dagger.compiler)
    implementation(libs.javax.inject)
    implementation(libs.koin.core)

    androidTestImplementation(libs.coroutines.android)

    androidTestImplementation(libs.benchmark.junit4)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}

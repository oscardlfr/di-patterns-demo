plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

// Toggle R8 on the release benchmark variant via:
//   ./gradlew :benchmark:connectedReleaseAndroidTest -Pminify=true
// Toggle debug benchmarking (numbers are inflated — sanity only) via:
//   ./gradlew :benchmark:connectedDebugAndroidTest -PbenchmarkBuildType=debug
val minifyBenchmarks = (project.findProperty("minify") as? String)?.toBoolean() ?: false
val benchmarkBuildType = (project.findProperty("benchmarkBuildType") as? String) ?: "release"

// Suppress flags differ per build type:
//   - release: standard set
//   - release+R8: add CODE-COVERAGE (R8 may strip JaCoCo if enabled elsewhere)
//   - debug: must add DEBUGGABLE (and accept that numbers are unreliable)
val suppressErrors = buildString {
    append("EMULATOR,LOW-BATTERY,ACTIVITY-MISSING")
    if (benchmarkBuildType == "debug") append(",DEBUGGABLE,METHOD-TRACING-ENABLED")
}

android {
    namespace = "com.grinwich.benchmark"
    compileSdk = 36
    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.benchmark.junit4.AndroidBenchmarkRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = suppressErrors
        testInstrumentationRunnerArguments["disableAnalytics"] = "true"
        // On emulator, disable method tracing to save disk space (~50% less):
        //   ./gradlew :benchmark:connectedReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.profiling.mode=None
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testBuildType = benchmarkBuildType
    buildTypes {
        debug {
            // Debug variant — only for sanity-checking that the benchmark
            // suite *runs* in a non-optimized build. Measurements are not
            // representative; D8 does not optimize like R8 and the runtime
            // overhead is significantly higher.
            isMinifyEnabled = false
        }
        release {
            isDefault = true
            isMinifyEnabled = minifyBenchmarks
            if (minifyBenchmarks) {
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "benchmark-proguard-rules.pro",
                )
            }
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

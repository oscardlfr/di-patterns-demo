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

// Suppress flags applied to BOTH variants (see `androidComponents` block below):
//   - EMULATOR,LOW-BATTERY,ACTIVITY-MISSING: always relevant
//   - DEBUGGABLE,METHOD-TRACING-ENABLED: required for the debug instrumented
//     variant (which we keep testable for orchestrator sweeps); inert on
//     release — the runner only triggers them when the variant is actually
//     debuggable / has method-tracing on.
val suppressErrors = "EMULATOR,LOW-BATTERY,ACTIVITY-MISSING,DEBUGGABLE,METHOD-TRACING-ENABLED"

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
    // `testBuildType = "release"` is what makes benchmark numbers trustworthy
    // (R8-optimized, non-debuggable). The `androidComponents` block below
    // ALSO enables androidTest on the debug variant — see that block for the
    // rationale (kmp-test-runner sweep compatibility).
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

// `testBuildType = "release"` above only generates androidTest for the
// release variant. kmp-test-runner's `parallel --test-type=androidInstrumented`
// dispatches `connectedDebugAndroidTest` unconditionally and would fail this
// module with `task_not_found`. Force-enable androidTest for the debug
// variant so both `connectedDebugAndroidTest` and `connectedReleaseAndroidTest`
// exist. Real benchmark numbers still come from release; debug runs are a
// sanity check only (DEBUGGABLE/METHOD-TRACING-ENABLED suppressors above
// allow them to complete).
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { variant ->
        variant.androidTest.enable = true
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

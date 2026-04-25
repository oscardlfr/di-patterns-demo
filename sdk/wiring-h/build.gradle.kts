plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.grinwich.sdk.wiring.h"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // PUBLIC -- app sees interfaces transitively
    api(project(":sdk:api"))

    // WIRING infra -- FeatureProvider, Resolver
    implementation(project(":di-contracts"))

    // RUNTIME ONLY -- ALL feature impls discovered via ServiceLoader
    // Zero compile-time coupling to any feature impl
    runtimeOnly(project(":features:feature-core-impl"))
    runtimeOnly(project(":features:feature-enc-impl"))
    runtimeOnly(project(":features:feature-auth-impl"))
    runtimeOnly(project(":features:feature-stor-impl"))
    runtimeOnly(project(":features:feature-ana-impl"))
    runtimeOnly(project(":features:feature-syn-impl"))
    runtimeOnly(project(":features:feature-observability-impl"))
}

// =====================================================================
// verifySdkClasspath — CI gate
// =====================================================================
// Confirms that every feature-impl this wiring declares as runtimeOnly
// is actually present on the releaseRuntimeClasspath. A missing entry
// means the SDK will silently fail with NoProviderFoundException at the
// first get() that needs it. Run as a hard gate before publishing.
val expectedFeatureImpls = listOf(
    ":features:feature-core-impl",
    ":features:feature-enc-impl",
    ":features:feature-auth-impl",
    ":features:feature-stor-impl",
    ":features:feature-ana-impl",
    ":features:feature-syn-impl",
    ":features:feature-observability-impl",
)

tasks.register("verifySdkClasspath") {
    group = "verification"
    description = "Asserts that every expected feature-impl is on the " +
        "releaseRuntimeClasspath of :sdk:wiring-h."

    // Resolve at execution time so configuration cache stays valid.
    doLast {
        val cfg = configurations.findByName("releaseRuntimeClasspath")
            ?: error("releaseRuntimeClasspath configuration missing — Android plugin not applied?")

        // Project paths actually present on the resolved classpath.
        val present = cfg.incoming.resolutionResult.allDependencies
            .mapNotNull { dep ->
                (dep as? org.gradle.api.artifacts.result.ResolvedDependencyResult)
                    ?.selected?.id
                    ?.let { it as? org.gradle.api.artifacts.component.ProjectComponentIdentifier }
                    ?.projectPath
            }
            .toSet()

        val missing = expectedFeatureImpls.filterNot { it in present }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "verifySdkClasspath FAILED — ${missing.size} expected " +
                "feature-impl(s) absent from releaseRuntimeClasspath:\n" +
                missing.joinToString("\n") { "  • $it" } +
                "\n\nThe SDK will throw NoProviderFoundException at runtime " +
                "for any service these modules were supposed to publish."
            )
        }
        logger.lifecycle(
            "verifySdkClasspath OK — ${expectedFeatureImpls.size} feature-impl(s) " +
            "present on releaseRuntimeClasspath."
        )
    }
}

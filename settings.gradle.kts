pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "di-patterns-demo"

// ── Observability ──
include(":observability-api")  // SdkLogger (interface) + AndroidSdkLogger (impl)

// ── Feature API modules (per-feature public interfaces) ──
include(":feature-core-api")   // SdkConfig
include(":feature-enc-api")
include(":feature-auth-api")
include(":feature-stor-api")
include(":feature-ana-api")
include(":feature-syn-api")

// ── SDK umbrella + monolithic patterns ──
include(":sdk:api")                // umbrella: re-exports all feature-apis + observability-api
include(":sdk:impl-common")
include(":sdk:impl-koin")
include(":sdk:impl-dagger-b")
include(":sdk:impl-dagger-c")
include(":sdk:impl-dagger-d")
include(":sdk:impl-dagger-e")
include(":sdk:impl-dagger-e2")
include(":sdk:di-core")
include(":sdk:impl-dagger-f")

// ── Multi-module (provision interfaces pattern) ──
include(":sdk:di-contracts")       // provision interfaces + scopes + RegistryInfra
include(":feature-core-impl")
include(":feature-enc-impl")
include(":feature-auth-impl")
include(":feature-stor-impl")
include(":feature-ana-impl")
include(":feature-syn-impl")

// ── Wiring variants (D=when-block, E=registry+toposort, E2=auto-init+DFS, G=factories) ──
include(":sdk:sdk-wiring")        // Pattern D: direct lazy ensure*()
include(":sdk:wiring-e")          // Pattern E: ProvisionRegistry + topo-sort
include(":sdk:wiring-e2")         // Pattern E2: AutoProvisionRegistry + DFS lazy
include(":sdk:wiring-g")          // Pattern G: factory functions (no DaggerXxx imports)

// ── Sample apps ──
include(":sample-dagger-a")
include(":sample-dagger-b")
include(":sample-dagger-c")
include(":sample-dagger-d")
include(":sample-dagger-e")
include(":sample-dagger-f")
include(":sample-dagger-e2")
include(":sample-hybrid")
include(":sample-multimodule")
include(":benchmark")

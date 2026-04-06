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

// ── Core API (SdkConfig, SdkLogger, CoreApis) ──
include(":sdk:core-api")

// ── Feature API modules (per-feature public interfaces) ──
include(":sdk:feature-enc-api")
include(":sdk:feature-auth-api")
include(":sdk:feature-stor-api")
include(":sdk:feature-ana-api")
include(":sdk:feature-syn-api")

// ── Per-feature contracts (provision interfaces + scopes) ──
include(":sdk:feature-core-contracts")
include(":sdk:feature-enc-contracts")
include(":sdk:feature-auth-contracts")
include(":sdk:feature-stor-contracts")
include(":sdk:feature-ana-contracts")
include(":sdk:feature-syn-contracts")

// ── SDK core modules (monolithic patterns) ──
include(":sdk:api")                // umbrella: re-exports core-api + all feature-apis
include(":sdk:impl-common")
include(":sdk:impl-koin")
include(":sdk:impl-dagger-b")
include(":sdk:impl-dagger-c")
include(":sdk:impl-dagger-d")
include(":sdk:impl-dagger-e")
include(":sdk:impl-dagger-e2")
include(":sdk:di-core")
include(":sdk:impl-dagger-f")

// ── Multi-module realistic example (provision interfaces pattern) ──
include(":sdk:di-contracts")       // umbrella: re-exports all feature-contracts + RegistryInfra
include(":sdk:feature-core-impl")
include(":sdk:feature-enc-impl")
include(":sdk:feature-auth-impl")
include(":sdk:feature-stor-impl")
include(":sdk:feature-ana-impl")
include(":sdk:feature-syn-impl")

// ── Wiring variants (D=when-block, E=registry+toposort, E2=auto-init+DFS) ──
include(":sdk:sdk-wiring")        // Pattern D: direct lazy ensure*()
include(":sdk:wiring-e")          // Pattern E: ProvisionRegistry + topo-sort
include(":sdk:wiring-e2")         // Pattern E2: AutoProvisionRegistry + DFS lazy

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

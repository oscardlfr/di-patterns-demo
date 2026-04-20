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

// ── Features ──
include(":features:observability-api")
include(":features:feature-enc-api")
include(":features:feature-auth-api")
include(":features:feature-stor-api")
include(":features:feature-ana-api")
include(":features:feature-syn-api")
include(":features:feature-core-impl")
include(":features:feature-enc-impl")
include(":features:feature-auth-impl")
include(":features:feature-stor-impl")
include(":features:feature-ana-impl")
include(":features:feature-syn-impl")
include(":features:feature-observability-impl")

// ── DI Infrastructure ──
include(":di-contracts")
include(":di-contracts-koin")

// ── SDK ──
include(":sdk:api")
include(":sdk:impl-common-d-c")
include(":sdk:impl-koin")
include(":sdk:impl-dagger-b")
include(":sdk:impl-dagger-c")
include(":sdk:sdk-wiring")
include(":sdk:wiring-e")
include(":sdk:wiring-e2")
include(":sdk:wiring-g")
include(":sdk:wiring-h")
include(":sdk:wiring-i")
include(":sdk:wiring-j")
include(":sdk:wiring-k")
include(":sdk:wiring-l")
include(":sdk:wiring-m")
include(":sdk:wiring-n")
include(":sdk:wiring-o")
include(":sdk:wiring-p")
include(":sdk:wiring-q")
include(":sdk:wiring-o2")
include(":sdk:wiring-p2")
include(":sdk:wiring-q2")

// ── Sample apps ──
include(":sample-dagger-a")
include(":sample-dagger-b")
include(":sample-dagger-c")
include(":sample-hybrid")
include(":sample-multimodule")
include(":benchmark")

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

// SDK modules
include(":sdk:api")
include(":sdk:impl-common")
include(":sdk:impl-koin")
include(":sdk:impl-dagger-b")
include(":sdk:impl-dagger-c")
include(":sdk:impl-dagger-d")
include(":sdk:impl-dagger-e")
include(":sdk:di-core")
include(":sdk:impl-dagger-e2")
include(":sdk:impl-dagger-f")

// Sample apps
include(":sample-dagger-a")
include(":sample-dagger-b")
include(":sample-dagger-c")
include(":sample-dagger-d")
include(":sample-dagger-e")
include(":sample-dagger-f")
include(":sample-dagger-e2")
include(":sample-hybrid")
include(":benchmark")

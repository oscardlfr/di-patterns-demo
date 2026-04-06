plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.grinwich.sdk.contracts"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Umbrella: re-exports all per-feature contracts so wiring modules need only one dep
    api(project(":sdk:feature-core-contracts"))
    api(project(":sdk:feature-enc-contracts"))
    api(project(":sdk:feature-auth-contracts"))
    api(project(":sdk:feature-stor-contracts"))
    api(project(":sdk:feature-ana-contracts"))
    api(project(":sdk:feature-syn-contracts"))

    // RegistryInfra.kt lives here — no external deps needed (pure Kotlin generics)
}

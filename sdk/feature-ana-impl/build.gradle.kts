plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.grinwich.sdk.feature.ana"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":sdk:feature-ana-contracts"))   // AnaProvisions, AnaScope
    api(project(":sdk:feature-core-contracts"))  // CoreProvisions (component dependency)
    implementation(project(":sdk:impl-common"))  // DefaultAnalyticsService

    implementation(libs.dagger)
    ksp(libs.dagger.compiler)
    implementation(libs.javax.inject)
}

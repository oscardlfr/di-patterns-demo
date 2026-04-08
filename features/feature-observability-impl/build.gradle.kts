plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.grinwich.sdk.feature.observability"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":di-contracts"))  // ObservabilityProvisions, Resolver
    implementation(project(":features:observability-api"))  // SdkLogger

    implementation(libs.dagger)
    ksp(libs.dagger.compiler)
    implementation(libs.javax.inject)
}

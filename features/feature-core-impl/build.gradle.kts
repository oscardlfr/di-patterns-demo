plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.grinwich.sdk.feature.core"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":di-contracts"))  // CoreProvisions, Resolver
    implementation(project(":features:feature-core-api"))  // SdkConfig

    implementation(libs.dagger)
    ksp(libs.dagger.compiler)
    implementation(libs.javax.inject)
}

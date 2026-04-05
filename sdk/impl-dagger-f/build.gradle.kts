plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.grinwich.sdk.modular"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":sdk:impl-common"))
    implementation(project(":sdk:di-core"))
    implementation(libs.dagger)
    ksp(libs.dagger.compiler)
    implementation(libs.javax.inject)
}

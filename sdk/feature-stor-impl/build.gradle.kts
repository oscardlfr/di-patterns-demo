plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.grinwich.sdk.feature.stor"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":sdk:feature-stor-contracts"))  // StorProvisions, StorScope
    api(project(":sdk:feature-core-contracts"))  // CoreProvisions (component dependency)
    api(project(":sdk:feature-enc-contracts"))   // EncProvisions (cross-feature contract)
    implementation(project(":sdk:impl-common"))  // DefaultSecureStorageService

    implementation(libs.dagger)
    ksp(libs.dagger.compiler)
    implementation(libs.javax.inject)
}

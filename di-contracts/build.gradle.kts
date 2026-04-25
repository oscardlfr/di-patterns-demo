plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.grinwich.sdk.contracts"
    compileSdk = 36
    defaultConfig {
        minSdk = 28
        // Propagate ServiceLoader keep rules to every consumer that depends
        // on this module, transitively or otherwise.
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // di-contracts es 100% NEUTRO. NO importa ningún tipo de API del SDK.
    // El Resolver maneja servicios como `Class<*> → instancia` sin conocer
    // tipos concretos. Los providers concretos (feature-*-impl) SÍ importan
    // los tipos, pero `di-contracts` no.
    //
    // javax.inject sólo para @Scope (annotations genéricas).
    implementation(libs.javax.inject)

    testImplementation(libs.junit)
}

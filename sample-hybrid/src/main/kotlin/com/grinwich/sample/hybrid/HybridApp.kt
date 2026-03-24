package com.grinwich.sample.hybrid

import android.app.Application
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.impl.KoinSdk
import com.grinwich.sdk.impl.SdkModule
import com.grinwich.sample.hybrid.di.DaggerSdkBridgeComponent
import com.grinwich.sample.hybrid.di.SdkBridgeComponent

/**
 * Hybrid app: Dagger 2 for app DI, Koin for SDK internals.
 *
 * Init order:
 * 1. KoinSdk.init() — creates isolated koinApplication
 * 2. DaggerSdkBridgeComponent — pulls SDK services into Dagger graph
 */
class HybridApp : Application() {

    lateinit var bridgeComponent: SdkBridgeComponent
        private set

    override fun onCreate() {
        super.onCreate()

        // SDK FIRST — must exist before Dagger resolves bridge bindings
        KoinSdk.init(
            modules = setOf(SdkModule.Encryption.Default),
            config = SdkConfig(debug = true),
        )

        // Dagger bridge — pulls SDK services into app's DI graph
        bridgeComponent = DaggerSdkBridgeComponent.builder().build()
    }
}

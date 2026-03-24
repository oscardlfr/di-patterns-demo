package com.grinwich.sample.daggera

import android.app.Application
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sample.daggera.di.DaggerSdkComponent
import com.grinwich.sample.daggera.di.SdkComponent

class DaggerAApp : Application() {

    lateinit var sdkComponent: SdkComponent

    /**
     * APPROACH A — getOrInitFeature() IS NOT REAL
     *
     * All modules are compiled into the single @Component at build time.
     * This Set only tracks which features the app considers "active" for UI gating.
     * The code for ALL features (including unused ones) is in the binary.
     *
     * You cannot add a module to a Dagger @Component at runtime.
     * The graph is sealed at compile time — that's the trade-off for compile-time safety.
     */
    private val _activeModules = mutableSetOf("encryption")

    override fun onCreate() {
        super.onCreate()
        sdkComponent = DaggerSdkComponent.builder()
            .config(SdkConfig(debug = true))
            .build()
    }

    // "Lazy init" is fake — just tracks activation, services already exist
    fun getOrInitFeature(feature: String): Set<String> {
        if (feature in _activeModules) return emptySet()
        // In monolithic, everything is already in the graph.
        // We just flag it as "active".
        _activeModules.add(feature)
        return setOf(feature)
    }

    fun isFeatureActive(feature: String): Boolean = feature in _activeModules
    fun activeModules(): Set<String> = _activeModules.toSet()
}

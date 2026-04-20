package com.grinwich.sdk.wiring.k

import android.content.Context
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.contracts.Resolver
import com.grinwich.sdk.contracts.SyntheticFeatureProvider

/**
 * Pattern K: AndroidManifest metadata discovery (Firebase-style).
 *
 * ALL features discovered via `<meta-data>` entries attached to
 * [ComponentDiscoveryService] in each feature-impl module's AndroidManifest.xml.
 * Manifest merger aggregates them at build time — zero hardcoded defaults.
 *
 * Logger is resolved lazily from ObservabilityProvider on first access.
 * Persists across init/shutdown cycles (resolver caches it).
 */
object MultiModuleSdkK : com.grinwich.sdk.api.MultiModuleSdkApi {

    private val resolver = Resolver()
    private var _initialized = false

    override val isInitialized: Boolean get() = _initialized

    override val builtFeatureCount: Int get() = resolver.builtFeatureCount

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkK already initialized. Call shutdown() first." }
        resolver.register(SyntheticFeatureProvider(mapOf(
            SdkConfig::class.java to config,
            android.content.Context::class.java to context.applicationContext,
        )))

        ComponentDiscovery.discover(context).forEach { provider ->
            resolver.register(provider)
        }

        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkK not initialized." }
        return resolver.get(clazz)
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    override fun shutdown() {
        if (!_initialized) return
        resolver.clear()
        _initialized = false
    }
}

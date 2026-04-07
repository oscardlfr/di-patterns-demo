package com.grinwich.sdk.wiring.h

import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Resolver
import java.util.ServiceLoader

/**
 * Pattern H: Auto-Discovery with ServiceLoader.
 *
 * ALL features (including observability/logger) discovered via ServiceLoader.
 * Zero hardcoded defaults, zero impl imports.
 *
 * Logger is resolved lazily from ObservabilityProvider on first access.
 * Persists across init/shutdown cycles (resolver caches it).
 */
object MultiModuleSdkH {

    private val resolver = Resolver()
    private var _initialized = false

    val isInitialized: Boolean get() = _initialized

    fun init(config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkH already initialized. Call shutdown() first." }
        resolver.init(config)

        ServiceLoader.load(FeatureProvider::class.java).forEach { provider ->
            resolver.register(provider)
        }

        _initialized = true
    }

    fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkH not initialized." }
        return resolver.get(clazz)
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    fun shutdown() {
        if (!_initialized) return
        resolver.clear()
        _initialized = false
    }
}

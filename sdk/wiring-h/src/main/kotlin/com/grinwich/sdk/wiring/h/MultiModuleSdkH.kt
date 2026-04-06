package com.grinwich.sdk.wiring.h

import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Resolver
import com.grinwich.sdk.feature.ana.AnaProvider
import com.grinwich.sdk.feature.auth.AuthProvider
import com.grinwich.sdk.feature.core.CoreProvider
import com.grinwich.sdk.feature.enc.EncProvider
import com.grinwich.sdk.feature.observability.AndroidSdkLogger
import com.grinwich.sdk.feature.stor.StorProvider
import com.grinwich.sdk.feature.syn.SynProvider

/**
 * Pattern H: Auto-Discovery with FeatureProviders.
 *
 * Each feature-impl declares a FeatureProvider (~8 lines).
 * Dependencies are implicit — resolver.provision() triggers DFS.
 * No when-blocks, no ensureXxx(), no central editing.
 *
 * For real production: use ServiceLoader to discover providers.
 * Here we list them explicitly for benchmark clarity.
 */
object MultiModuleSdkH {

    private val resolver = Resolver()
    private var _initialized = false

    val isInitialized: Boolean get() = _initialized

    fun init(config: SdkConfig, logger: SdkLogger = AndroidSdkLogger()) {
        check(!_initialized) { "MultiModuleSdkH already initialized. Call shutdown() first." }
        resolver.init(logger)

        // Register all providers — in production, ServiceLoader discovers these
        resolver.register(CoreProvider(config))
        resolver.register(EncProvider())
        resolver.register(AuthProvider())
        resolver.register(StorProvider())
        resolver.register(AnaProvider())
        resolver.register(SynProvider())

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

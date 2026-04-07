package com.grinwich.sdk.wiring.h

import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Resolver
import com.grinwich.sdk.feature.observability.AndroidSdkLogger
import java.util.ServiceLoader

/**
 * Pattern H: Auto-Discovery with ServiceLoader.
 *
 * Each feature-impl registers a FeatureProvider in META-INF/services.
 * ServiceLoader discovers them at init — zero explicit registration.
 * Dependencies are implicit — resolver.provision() triggers DFS.
 *
 * Logger persists across init/shutdown cycles — set once at first init
 * or via setLogger(), never lost on reinit.
 */
object MultiModuleSdkH {

    private val resolver = Resolver()
    private var _logger: SdkLogger = AndroidSdkLogger()
    private var _initialized = false

    val isInitialized: Boolean get() = _initialized

    /** Override the default logger. Persists across init/shutdown cycles. */
    fun setLogger(logger: SdkLogger) {
        _logger = logger
    }

    fun init(config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkH already initialized. Call shutdown() first." }
        resolver.init(config, _logger)

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
        resolver.clear()    // clears provisions + services, NOT logger
        _initialized = false
    }
}

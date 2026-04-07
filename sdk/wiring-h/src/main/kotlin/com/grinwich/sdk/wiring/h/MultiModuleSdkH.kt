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
 * Adding feature 51:
 * 1. Create feature-xxx-impl module
 * 2. Write @Component + @Module + DefaultXxxService + factory
 * 3. Write XxxProvider : FeatureProvider (~8 lines)
 * 4. Register in META-INF/services (1 line)
 * 5. Add runtimeOnly dep in this module's build.gradle.kts
 * Zero other files touched.
 */
object MultiModuleSdkH {

    private val resolver = Resolver()
    private var _initialized = false

    val isInitialized: Boolean get() = _initialized

    fun init(config: SdkConfig, logger: SdkLogger = AndroidSdkLogger()) {
        check(!_initialized) { "MultiModuleSdkH already initialized. Call shutdown() first." }
        resolver.init(config, logger)

        // ServiceLoader discovers all FeatureProviders on classpath
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

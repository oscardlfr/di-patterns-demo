package com.grinwich.sdk.daggerc

import com.grinwich.sdk.api.*
import com.grinwich.sdk.common.*
import java.util.ServiceLoader

/**
 * Approach C SDK facade — ServiceLoader auto-discovery.
 *
 * Same public API as KoinSdk / DaggerBSdk / DaggerSdk(D).
 * Internally: ServiceLoader discovers FeatureInitializers from META-INF/services.
 * Adding a feature = add Gradle dep + META-INF entry. Zero edits to this class.
 *
 * Limitations:
 * - JVM-only (ServiceLoader requires META-INF/services)
 * - Runtime errors if feature dep not on classpath
 * - Same CoreApis wiring issue as B internally
 */
object DaggerCSdk {

    internal val foundationLogger: SdkLogger = AndroidSdkLogger()

    private val _initializers = mutableMapOf<String, FeatureInitializer>()
    private var _available: Map<String, FeatureInitializer>? = null
    private var _initialized = false
    private var _core: CoreApis? = null

    val isInitialized: Boolean get() = _initialized
    val initializedModules: Set<String> get() = _initializers.keys.toSet()

    private fun discover(): Map<String, FeatureInitializer> =
        _available ?: ServiceLoader.load(FeatureInitializer::class.java)
            .associateBy { it.featureName }.also { _available = it }

    private val resolver = object : ServiceResolver {
        override fun <T> resolve(serviceClass: Class<T>): T? {
            for (init in _initializers.values) {
                init.getService(serviceClass)?.let { return it }
            }
            return null
        }
    }

    fun init(config: SdkConfig, features: Set<String>) {
        check(!_initialized) { "DaggerCSdk already initialized." }
        require(features.isNotEmpty()) { "features must not be empty." }
        _core = CoreApisImpl(config, foundationLogger)
        _initialized = true
        for (f in features) getOrInitModule(f)
    }

    fun getOrInitModule(feature: String): Set<String> {
        check(_initialized) { "DaggerCSdk not initialized." }
        if (feature in _initializers) return emptySet()

        val available = discover()
        val init = available[feature]
            ?: throw IllegalArgumentException("Feature '$feature' not on classpath. Available: ${available.keys}")

        val inited = mutableSetOf<String>()
        for (dep in init.requiredDependencies) {
            if (dep !in _initializers) inited += getOrInitModule(dep)
        }

        init.init(_core!!, resolver)
        _initializers[feature] = init
        inited.add(feature)
        return inited
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "DaggerCSdk not initialized." }
        for (init in _initializers.values) {
            init.getService(clazz)?.let { return it }
        }
        error("Service ${clazz.simpleName} not found.")
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    fun shutdown() {
        _initializers.values.forEach { it.shutdown() }
        _initializers.clear()
        _initialized = false; _core = null
    }
}

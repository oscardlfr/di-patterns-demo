package com.grinwich.sdk.wiring.e

import com.grinwich.sdk.api.*
import com.grinwich.sdk.feature.observability.AndroidSdkLogger
import com.grinwich.sdk.contracts.*

/**
 * Multi-module SDK facade — Pattern E (Registry with topological sort).
 *
 * Same architecture as monolithic RegistrySdk but:
 * - Entries keyed by PROVISION INTERFACES (CoreProvisions, EncProvisions, ...)
 *   instead of concrete @Component classes
 * - Feature impls in separate Gradle modules, only knowing provision interfaces
 * - This wiring module is the ONLY place importing DaggerXxxComponent
 *
 * Logger persists across init/shutdown cycles — set once at first init
 * or via setLogger(), never lost on reinit.
 *
 * Consumer API: init(config, features) -> getOrInitModule() -> get<T>() -> shutdown()
 */
object MultiModuleSdkE {

    enum class Feature(
        internal val provisionClass: Class<*>,
        internal val requiredDeps: Set<Feature> = emptySet(),
    ) {
        ENCRYPTION(EncProvisions::class.java),
        AUTH(AuthProvisions::class.java, setOf(ENCRYPTION)),
        STORAGE(StorProvisions::class.java, setOf(ENCRYPTION)),
        ANALYTICS(AnaProvisions::class.java),
        SYNC(SynProvisions::class.java, setOf(ENCRYPTION, AUTH, STORAGE));

        /** Transitive closure of all dependencies. */
        internal fun allDependencies(): Set<Feature> {
            val result = mutableSetOf<Feature>()
            fun collect(f: Feature) {
                for (dep in f.requiredDeps) {
                    if (result.add(dep)) collect(dep)
                }
            }
            collect(this)
            return result
        }
    }

    private var _initialized = false
    private var _logger: SdkLogger = AndroidSdkLogger()
    private val registry = ProvisionRegistry()
    private val _initializedFeatures = mutableSetOf<Feature>()

    val isInitialized: Boolean get() = _initialized

    /** Override the default logger. Persists across init/shutdown cycles. */
    fun setLogger(logger: SdkLogger) {
        _logger = logger
    }

    /**
     * Initialize with selected features. Core is always built.
     * Dependencies are resolved automatically via topological sort.
     */
    fun init(
        config: SdkConfig,
        features: Set<Feature> = emptySet(),
    ) {
        check(!_initialized) { "MultiModuleSdkE already initialized." }

        // Always register core
        registry.register(coreEntry(config, _logger))

        // Expand transitive deps + topo-sort
        val allFeatures = features.flatMap { setOf(it) + it.allDependencies() }.toSet()
        if (allFeatures.isNotEmpty()) {
            val entries = allFeatures.map { featureToEntry(it) }
            registry.registerAll(entries)
            _initializedFeatures.addAll(allFeatures)
        }

        _initialized = true
    }

    /**
     * Lazily add a feature (and its deps) to the running graph.
     */
    fun getOrInitModule(feature: Feature): Set<Feature> {
        check(_initialized) { "MultiModuleSdkE not initialized." }
        if (feature in _initializedFeatures) return emptySet()

        val needed = (setOf(feature) + feature.allDependencies())
            .filter { it !in _initializedFeatures }
            .toSet()

        if (needed.isNotEmpty()) {
            val entries = needed.map { featureToEntry(it) }
            registry.registerAll(entries)
            _initializedFeatures.addAll(needed)
        }
        return needed
    }

    fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkE not initialized." }
        return registry.get(clazz)
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    fun shutdown() {
        if (!_initialized) return
        registry.clear()
        _initializedFeatures.clear()
        _initialized = false
    }

    private fun featureToEntry(feature: Feature): ProvisionEntry<*> = when (feature) {
        Feature.ENCRYPTION -> encEntry(_logger)
        Feature.AUTH -> authEntry(_logger)
        Feature.STORAGE -> storEntry(_logger)
        Feature.ANALYTICS -> anaEntry(_logger)
        Feature.SYNC -> synEntry(_logger)
    }
}

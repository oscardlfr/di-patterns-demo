package com.grinwich.sdk.wiring.e

import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.ServiceEntry
import com.grinwich.sdk.contracts.ServiceRegistry
import com.grinwich.sdk.feature.ana.AnaFeatureId
import com.grinwich.sdk.feature.auth.AuthFeatureId
import com.grinwich.sdk.feature.enc.EncFeatureId
import com.grinwich.sdk.feature.stor.StorFeatureId
import com.grinwich.sdk.feature.syn.SynFeatureId

/**
 * Multi-module SDK facade — Pattern E (Registry with topological sort).
 *
 * Entries key by FeatureId (neutral marker class) instead of `Provisions`.
 * Each feature-impl declares its marker alongside the factory that builds it.
 * This wiring is the only place that knows `DaggerXxxComponent` — indirectly,
 * through public factories (`buildXxxService`, `buildEncBundle`).
 *
 * Consumer API: `init(context, config, features) → getOrInitModule() →
 * get<T>() → shutdown()`.
 */
object MultiModuleSdkE {

    enum class Feature(
        internal val featureId: Class<*>,
        internal val requiredDeps: Set<Feature> = emptySet(),
    ) {
        ENCRYPTION(EncFeatureId::class.java),
        AUTH(AuthFeatureId::class.java, setOf(ENCRYPTION)),
        STORAGE(StorFeatureId::class.java, setOf(ENCRYPTION)),
        ANALYTICS(AnaFeatureId::class.java),
        SYNC(SynFeatureId::class.java, setOf(ENCRYPTION, AUTH, STORAGE));

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
    private val registry = ServiceRegistry()
    private val _initializedFeatures = mutableSetOf<Feature>()

    val isInitialized: Boolean get() = _initialized

    val builtFeatureCount: Int get() = registry.builtFeatureCount

    fun init(
        context: android.content.Context,
        config: SdkConfig,
        features: Set<Feature> = emptySet(),
    ) {
        check(!_initialized) { "MultiModuleSdkE already initialized." }

        registry.register(observabilityEntry())
        registry.register(coreEntry(config, context))

        val allFeatures = features.flatMap { setOf(it) + it.allDependencies() }.toSet()
        if (allFeatures.isNotEmpty()) {
            val entries = allFeatures.map { featureToEntry(it) }
            registry.registerAll(entries)
            _initializedFeatures.addAll(allFeatures)
        }

        _initialized = true
    }

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

    private fun featureToEntry(feature: Feature): ServiceEntry = when (feature) {
        Feature.ENCRYPTION -> encEntry()
        Feature.AUTH -> authEntry()
        Feature.STORAGE -> storEntry()
        Feature.ANALYTICS -> anaEntry()
        Feature.SYNC -> synEntry()
    }
}

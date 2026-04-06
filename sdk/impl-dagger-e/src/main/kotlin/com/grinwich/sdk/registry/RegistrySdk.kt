package com.grinwich.sdk.registry

import com.grinwich.sdk.api.*
import com.grinwich.sdk.api.AndroidSdkLogger

/**
 * Pattern E: Component Registry SDK facade.
 *
 * Public API is identical to Pattern D: init(config, features), get<T>(), shutdown().
 * The app only sees [Feature] enum — never touches entries, components, or registry.
 *
 * Internally uses [ComponentRegistry] with:
 * 1. Explicit service bindings (no reflection)
 * 2. Eager resolution (instances, not lambdas)
 * 3. HashMap (not ConcurrentHashMap — init single-threaded, post-init read-only)
 * 4. Auto topological sort (entries registered in dependency order)
 *
 * Corporate multi-module setup:
 *   Each Gradle module (:integration:features:storage, etc.) declares its
 *   [FeatureEntry] internally. This SDK integration module collects them all.
 *   The app module depends only on :sdk:api and this facade — never sees entries.
 */
object RegistrySdk {

    internal val foundationLogger: SdkLogger = AndroidSdkLogger()

    private var _initialized = false
    private val _initializedFeatures = mutableSetOf<Feature>()
    private var registry = ComponentRegistry()

    val isInitialized: Boolean get() = _initialized
    val initializedModules: Set<Feature> get() = _initializedFeatures.toSet()

    /**
     * Public feature selectors — the ONLY thing the app sees.
     * Internal mapping to [FeatureEntry] stays inside the SDK.
     */
    enum class Feature(internal val entry: FeatureEntry<out DiComponent>) {
        ENCRYPTION(encryptionEntry),
        AUTH(authEntry),
        STORAGE(storageEntry),
        ANALYTICS(analyticsEntry),
        SYNC(syncEntry);

        internal val requiredDependencies: Set<Feature>
            get() = when (this) {
                ENCRYPTION -> emptySet()
                AUTH -> setOf(ENCRYPTION)
                STORAGE -> setOf(ENCRYPTION)
                ANALYTICS -> emptySet()
                SYNC -> setOf(AUTH, STORAGE, ENCRYPTION)
            }
    }

    fun init(config: SdkConfig, features: Set<Feature>) {
        check(!_initialized) { "RegistrySdk already initialized. Call shutdown() first." }
        require(features.isNotEmpty()) { "features must not be empty." }

        registry = ComponentRegistry()
        registry.register(coreEntry(config))

        // Expand all transitive dependencies and register via topo-sort
        val allFeatures = expandDependencies(features)
        val entries = allFeatures.map { it.entry }
        registry.registerAll(entries)
        _initializedFeatures.addAll(allFeatures)
        _initialized = true
    }

    /**
     * Lazy init — add a feature to the running SDK.
     * Cascades dependencies automatically.
     */
    fun getOrInitModule(feature: Feature): Set<Feature> {
        check(_initialized) { "RegistrySdk not initialized. Call init() first." }
        if (feature in _initializedFeatures) return emptySet()

        val needed = expandDependencies(setOf(feature)) - _initializedFeatures
        if (needed.isEmpty()) return emptySet()

        val entries = needed.map { it.entry }
        registry.registerAll(entries)
        _initializedFeatures.addAll(needed)
        return needed
    }

    fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "RegistrySdk not initialized." }
        return registry.get(clazz)
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    fun shutdown() {
        if (!_initialized) return
        registry.clear()
        _initializedFeatures.clear()
        _initialized = false
    }

    /** Expand a set of features to include all transitive dependencies. */
    private fun expandDependencies(features: Set<Feature>): Set<Feature> {
        val result = mutableSetOf<Feature>()
        fun visit(f: Feature) {
            if (f in result) return
            for (dep in f.requiredDependencies) visit(dep)
            result.add(f)
        }
        features.forEach { visit(it) }
        return result
    }
}

package com.grinwich.sdk.dagger

import com.grinwich.sdk.api.*
import com.grinwich.sdk.common.AndroidSdkLogger

/**
 * Dagger 2 SDK facade — same public API as KoinSdk.
 *
 * Uses Component Dependencies (approach D) internally.
 * Consumer sees: init(), getOrInitModule(), get<T>(), shutdown().
 * Consumer does NOT see: DaggerComponents, @Module, @Provides.
 *
 * Internal architecture:
 *   CoreComponent → EncComponent → AuthComponent / StorComponent → SyncComponent
 *   Each child depends on its parents via `dependencies = [...]`
 *   Cross-feature deps resolved by Dagger automatically.
 */
object DaggerSdk {

    // FoundationSingletons — survives shutdown() → init()
    internal val foundationLogger: SdkLogger = AndroidSdkLogger()

    private var _initialized = false
    private var _initializedModules = mutableSetOf<Feature>()

    private var _core: CoreComponent? = null
    private var _enc: EncComponent? = null
    private var _auth: AuthComponent? = null
    private var _storage: StorComponent? = null
    private var _analytics: AnaComponent? = null
    private var _sync: SynComponent? = null

    val isInitialized: Boolean get() = _initialized
    val initializedModules: Set<Feature> get() = _initializedModules.toSet()

    /**
     * Type-safe feature selectors — same concept as KoinSdk's sealed SdkModule.
     */
    enum class Feature {
        ENCRYPTION, AUTH, STORAGE, ANALYTICS, SYNC;

        val requiredDependencies: Set<Feature>
            get() = when (this) {
                ENCRYPTION -> emptySet()
                AUTH -> setOf(ENCRYPTION)
                STORAGE -> setOf(ENCRYPTION)
                ANALYTICS -> emptySet()
                SYNC -> setOf(AUTH, STORAGE, ENCRYPTION)
            }
    }

    fun init(config: SdkConfig, features: Set<Feature>) {
        check(!_initialized) { "DaggerSdk already initialized. Call shutdown() first." }
        require(features.isNotEmpty()) { "features must not be empty." }

        _core = DaggerCoreComponent.builder().config(config).build()
        _initialized = true

        for (feature in features) {
            getOrInitModule(feature)
        }
    }

    /**
     * Lazy init — add a feature to the running SDK.
     *
     * Cascades dependencies automatically:
     *   getOrInitModule(SYNC) → inits AUTH → inits ENCRYPTION → inits STORAGE
     *
     * Returns the set of features actually initialized (including cascaded).
     */
    fun getOrInitModule(feature: Feature): Set<Feature> {
        check(_initialized) { "DaggerSdk not initialized. Call init() first." }
        if (feature in _initializedModules) return emptySet()

        val inited = mutableSetOf<Feature>()
        for (dep in feature.requiredDependencies) {
            if (dep !in _initializedModules) {
                inited += getOrInitModule(dep)
            }
        }

        val core = _core!!
        when (feature) {
            Feature.ENCRYPTION -> {
                _enc = DaggerEncComponent.builder().core(core).build()
            }
            Feature.AUTH -> {
                _auth = DaggerAuthComponent.builder().core(core).enc(_enc!!).build()
            }
            Feature.STORAGE -> {
                _storage = DaggerStorComponent.builder().core(core).enc(_enc!!).build()
            }
            Feature.ANALYTICS -> {
                _analytics = DaggerAnaComponent.builder().core(core).build()
            }
            Feature.SYNC -> {
                _sync = DaggerSynComponent.builder()
                    .core(core).enc(_enc!!).auth(_auth!!).storage(_storage!!)
                    .build()
            }
        }
        _initializedModules.add(feature)
        inited.add(feature)
        return inited
    }

    /**
     * Resolve a service by type — same as KoinSdk.get<T>().
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "DaggerSdk not initialized." }
        return when (clazz) {
            EncryptionService::class.java -> _enc?.encryption()
            HashService::class.java -> _enc?.hash()
            AuthService::class.java -> _auth?.auth()
            SecureStorageService::class.java -> _storage?.storage()
            AnalyticsService::class.java -> _analytics?.analytics()
            SyncService::class.java -> _sync?.sync()
            SdkLogger::class.java -> foundationLogger
            else -> null
        } as? T ?: error("Service ${clazz.simpleName} not available. Did you init the right module?")
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    fun requireModule(feature: Feature) {
        check(_initialized) { "DaggerSdk not initialized." }
        check(feature in _initializedModules) {
            "Feature ${feature.name} not initialized. Call getOrInitModule() or add it to init()."
        }
    }

    fun shutdown() {
        if (!_initialized) return
        _core = null; _enc = null; _auth = null
        _storage = null; _analytics = null; _sync = null
        _initialized = false
        _initializedModules.clear()
        // foundationLogger survives — it's a val on the object
    }
}

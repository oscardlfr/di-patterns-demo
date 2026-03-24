package com.grinwich.sdk.daggerb

import com.grinwich.sdk.api.*
import com.grinwich.sdk.common.*

/**
 * Approach B SDK facade — Per-Feature Components with CoreApis bridge.
 *
 * Same public API as KoinSdk / DaggerSdk(D).
 * Internally: N separate DaggerComponents, cross-feature deps via extended CoreApis.
 *
 * Limitation: each cross-feature dep adds a field to an extended CoreApis interface.
 * At 15+ shared services, these interfaces become a God Object.
 */
object DaggerBSdk {

    internal val foundationLogger: SdkLogger = AndroidSdkLogger()

    enum class Feature {
        ENCRYPTION, AUTH, STORAGE, ANALYTICS, SYNC;
        val requiredDependencies: Set<Feature> get() = when (this) {
            ENCRYPTION -> emptySet()
            AUTH -> setOf(ENCRYPTION)
            STORAGE -> setOf(ENCRYPTION)
            ANALYTICS -> emptySet()
            SYNC -> setOf(AUTH, STORAGE, ENCRYPTION)
        }
    }

    private var _initialized = false
    private var _initializedModules = mutableSetOf<Feature>()
    private var _core: CoreApis? = null

    private var _enc: IntEncComp? = null
    private var _auth: IntAuthComp? = null
    private var _storage: IntStorComp? = null
    private var _analytics: IntAnaComp? = null
    private var _sync: IntSynComp? = null

    val isInitialized: Boolean get() = _initialized
    val initializedModules: Set<Feature> get() = _initializedModules.toSet()

    fun init(config: SdkConfig, features: Set<Feature>) {
        check(!_initialized) { "DaggerBSdk already initialized." }
        require(features.isNotEmpty()) { "features must not be empty." }
        _core = CoreApisImpl(config, foundationLogger)
        _initialized = true
        for (f in features) getOrInitModule(f)
    }

    fun getOrInitModule(feature: Feature): Set<Feature> {
        check(_initialized) { "DaggerBSdk not initialized." }
        if (feature in _initializedModules) return emptySet()

        val inited = mutableSetOf<Feature>()
        for (dep in feature.requiredDependencies) {
            if (dep !in _initializedModules) inited += getOrInitModule(dep)
        }

        val core = _core!!
        when (feature) {
            Feature.ENCRYPTION -> {
                _enc = DaggerIntEncComp.builder().core(core).build()
            }
            Feature.AUTH -> {
                // CoreApis extended with EncryptionService — manual bridge
                val authCore = AuthCoreApisImpl(core, _enc!!.encryption())
                _auth = DaggerIntAuthComp.builder().core(authCore).build()
            }
            Feature.STORAGE -> {
                val storCore = StorCoreApisImpl(core, _enc!!.encryption(), _enc!!.hash())
                _storage = DaggerIntStorComp.builder().core(storCore).build()
            }
            Feature.ANALYTICS -> {
                _analytics = DaggerIntAnaComp.builder().core(core).build()
            }
            Feature.SYNC -> {
                // Mega-CoreApis with ALL cross-feature deps — the God Object pattern
                val syncCore = SyncCoreApisImpl(
                    core, _auth!!.auth(), _storage!!.storage(), _enc!!.encryption()
                )
                _sync = DaggerIntSynComp.builder().core(syncCore).build()
            }
        }
        _initializedModules.add(feature)
        inited.add(feature)
        return inited
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "DaggerBSdk not initialized." }
        return when (clazz) {
            EncryptionService::class.java -> _enc?.encryption()
            HashService::class.java -> _enc?.hash()
            AuthService::class.java -> _auth?.auth()
            SecureStorageService::class.java -> _storage?.storage()
            AnalyticsService::class.java -> _analytics?.analytics()
            SyncService::class.java -> _sync?.sync()
            SdkLogger::class.java -> foundationLogger
            else -> null
        } as? T ?: error("Service ${clazz.simpleName} not available.")
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    fun shutdown() {
        if (!_initialized) return
        _core = null; _enc = null; _auth = null
        _storage = null; _analytics = null; _sync = null
        _initialized = false; _initializedModules.clear()
    }
}

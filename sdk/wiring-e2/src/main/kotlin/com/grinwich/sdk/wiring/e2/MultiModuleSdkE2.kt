package com.grinwich.sdk.wiring.e2

import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.AutoServiceRegistry
import com.grinwich.sdk.contracts.error.DependencyResolutionException

/**
 * Multi-module SDK facade — Pattern E2 (Auto-Init Registry).
 *
 * Evolution over MultiModuleSdkE:
 * - NO Feature enum.
 * - NO getOrInitModule().
 * - `get<T>()` auto-discovers and builds the component chain on demand.
 *
 * Consumer API: `init(context, config)` → `get<T>()` → `shutdown()`.
 * The simplest possible consumer surface — identical to the monolithic AutoSdk.
 */
object MultiModuleSdkE2 : MultiModuleSdkApi {

    /** Serializes [init] and [shutdown]; [get] runs lock-free. */
    private val lifecycleLock = Any()
    private var _initialized = false
    private val registry = AutoServiceRegistry()

    override val isInitialized: Boolean get() = _initialized

    override val builtFeatureCount: Int get() = registry.builtFeatureCount

    /**
     * Initializes the SDK. Only catalogs entries (cheap HashMap puts).
     * Component construction happens lazily on the first `get<T>()`.
     */
    override fun init(context: android.content.Context, config: SdkConfig) {
        synchronized(lifecycleLock) {
            check(!_initialized) { "MultiModuleSdkE2 already initialized." }
            registry.installAll(allAutoEntries(context, config))
            _initialized = true
        }
    }

    /**
     * Resolves a service by type. Auto-builds the entry that provides it
     * (and all transitive dependencies) if not yet built.
     *
     * `get<SyncApi>()` → auto-builds: Observability → Core → Enc → Auth → Stor → Syn
     */
    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkE2 not initialized." }
        return try {
            registry.get(clazz)
        } catch (e: DependencyResolutionException) {
            // Concurrent shutdown can drain the registry between the
            // initialization check above and the resolution below. Surface
            // that race as a lifecycle error so callers only see two
            // contracts: either the SDK returns a service, or it reports
            // "not initialized".
            if (!_initialized) throw IllegalStateException("MultiModuleSdkE2 not initialized.", e)
            throw e
        }
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    override fun shutdown() {
        synchronized(lifecycleLock) {
            if (!_initialized) return
            registry.clear()
            _initialized = false
        }
    }
}

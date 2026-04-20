package com.grinwich.sdk.wiring.e2

import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.AutoServiceRegistry

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

    private var _initialized = false
    private val registry = AutoServiceRegistry()

    override val isInitialized: Boolean get() = _initialized

    override val builtFeatureCount: Int get() = registry.builtFeatureCount

    /**
     * Initializes the SDK. Only catalogs entries (cheap HashMap puts).
     * Component construction happens lazily on the first `get<T>()`.
     */
    override fun init(context: android.content.Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkE2 already initialized." }
        registry.installAll(allAutoEntries(context, config))
        _initialized = true
    }

    /**
     * Resolves a service by type. Auto-builds the entry that provides it
     * (and all transitive dependencies) if not yet built.
     *
     * `get<SyncApi>()` → auto-builds: Observability → Core → Enc → Auth → Stor → Syn
     */
    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkE2 not initialized." }
        return registry.get(clazz)
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    override fun shutdown() {
        if (!_initialized) return
        registry.clear()
        _initialized = false
    }
}

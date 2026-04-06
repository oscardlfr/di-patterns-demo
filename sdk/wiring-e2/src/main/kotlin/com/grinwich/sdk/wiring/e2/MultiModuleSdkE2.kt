package com.grinwich.sdk.wiring.e2

import com.grinwich.sdk.api.*
import com.grinwich.sdk.api.AndroidSdkLogger
import com.grinwich.sdk.contracts.AutoProvisionRegistry

/**
 * Multi-module SDK facade -- Pattern E2 (Auto-Init Registry).
 *
 * Evolution from MultiModuleSdkE:
 * - NO Feature enum
 * - NO getOrInitModule()
 * - get<T>() auto-discovers and builds the component chain on demand
 *
 * Consumer API: init(config) -> get<T>() -> shutdown()
 * Simplest possible consumer surface — identical to monolithic AutoSdk.
 */
object MultiModuleSdkE2 {

    private var _initialized = false
    private val registry = AutoProvisionRegistry()

    val isInitialized: Boolean get() = _initialized

    /**
     * Initialize the SDK. Only catalogs entries (cheap HashMap puts).
     * Actual component building happens lazily on first get<T>().
     */
    fun init(
        config: SdkConfig,
        logger: SdkLogger = AndroidSdkLogger(),
    ) {
        check(!_initialized) { "MultiModuleSdkE2 already initialized." }
        registry.installAll(allAutoEntries(config, logger))
        _initialized = true
    }

    /**
     * Resolve a service by type. Auto-builds the providing component
     * (and all transitive dependencies) if not yet built.
     *
     * get<SyncApi>() -> auto-builds: Core -> Enc -> Auth -> Stor -> Syn
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkE2 not initialized." }
        return registry.get(clazz)
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    fun shutdown() {
        if (!_initialized) return
        registry.clear()
        _initialized = false
    }
}

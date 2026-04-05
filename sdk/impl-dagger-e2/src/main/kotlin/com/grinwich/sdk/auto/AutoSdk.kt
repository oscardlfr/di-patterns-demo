package com.grinwich.sdk.auto

import com.grinwich.sdk.api.*
import com.grinwich.sdk.common.AndroidSdkLogger

/**
 * Pattern E2: Auto-Init Registry SDK facade.
 *
 * Evolution from E (Component Registry):
 * - NO Feature enum exposed — consumer never selects features
 * - NO getOrInitModule() — auto-init happens transparently on get<T>()
 * - Facade code NEVER changes when adding modules — only allEntries() grows
 *
 * Consumer API (the simplest of all approaches):
 * ```
 * AutoSdk.init(SdkConfig(debug = true))
 * val sync = AutoSdk.get<SyncService>()   // auto-inits Core→Enc→Auth→Stor→Sync
 * val enc  = AutoSdk.get<EncryptionService>() // already built — instant
 * AutoSdk.shutdown()
 * ```
 *
 * How it works:
 * 1. init() installs all entries into the catalog (just HashMap puts — ~50 ns)
 * 2. get<T>() finds the entry that provides T, builds it + all deps recursively
 * 3. Subsequent get<T>() hits the service cache directly (~2-4 ns)
 *
 * Scalability: adding a new module requires:
 * 1. Create AutoFeatureEntry in the module's di/ package
 * 2. Add it to allEntries() — ONE line
 * No enum cases. No when blocks. No per-component variables.
 *
 * Corporate multi-module: each :integration:features:X module exports its entry.
 * This facade module collects them via allEntries(). The app module depends only
 * on :sdk:api and this facade — never sees entries, components, or registry.
 */
object AutoSdk {

    private var registry = AutoRegistry()
    private var _initialized = false

    val isInitialized: Boolean get() = _initialized

    /**
     * Initialize the SDK. Catalogs all entries but builds NOTHING yet.
     * Actual component construction happens lazily on first get<T>().
     */
    fun init(config: SdkConfig, logger: SdkLogger = AndroidSdkLogger()) {
        check(!_initialized) { "AutoSdk already initialized. Call shutdown() first." }
        registry = AutoRegistry()
        registry.installAll(allEntries(config, logger))
        _initialized = true
    }

    /**
     * Resolve a service by type. If the component that provides it hasn't been
     * built yet, it (and all transitive dependencies) are built automatically.
     *
     * First access: recursive build of component chain.
     * Subsequent access: single HashMap.get (~2-4 ns).
     */
    inline fun <reified T : Any> get(): T = get(T::class.java)

    fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "AutoSdk not initialized. Call init() first." }
        return registry.get(clazz)
    }

    fun shutdown() {
        if (!_initialized) return
        registry.clear()
        _initialized = false
    }
}

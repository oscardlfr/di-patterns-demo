package com.grinwich.sdk.wiring.e2

import com.grinwich.sdk.api.*
import com.grinwich.sdk.feature.observability.AndroidSdkLogger
import com.grinwich.sdk.contracts.AutoProvisionRegistry

/**
 * Multi-module SDK facade -- Pattern E2 (Auto-Init Registry).
 *
 * Evolution from MultiModuleSdkE:
 * - NO Feature enum
 * - NO getOrInitModule()
 * - get<T>() auto-discovers and builds the component chain on demand
 *
 * Logger persists across init/shutdown cycles — set once at first init
 * or via setLogger(), never lost on reinit.
 *
 * Consumer API: init(config) -> get<T>() -> shutdown()
 * Simplest possible consumer surface — identical to monolithic AutoSdk.
 */
object MultiModuleSdkE2 : MultiModuleSdkApi {

    private var _initialized = false
    private var _logger: SdkLogger = AndroidSdkLogger()
    private val registry = AutoProvisionRegistry()

    override val isInitialized: Boolean get() = _initialized

    /** Number of provisions currently built. Useful for verifying lazy behavior in tests. */
    override val builtProvisionCount: Int get() = registry.builtProvisionCount

    /** Override the default logger. Persists across init/shutdown cycles. */
    fun setLogger(logger: SdkLogger) {
        _logger = logger
    }

    /**
     * Initialize the SDK. Only catalogs entries (cheap HashMap puts).
     * Actual component building happens lazily on first get<T>().
     */
    override fun init(context: android.content.Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkE2 already initialized." }
        registry.installAll(allAutoEntries(config, _logger))
        _initialized = true
    }

    /**
     * Resolve a service by type. Auto-builds the providing component
     * (and all transitive dependencies) if not yet built.
     *
     * get<SyncApi>() -> auto-builds: Core -> Enc -> Auth -> Stor -> Syn
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

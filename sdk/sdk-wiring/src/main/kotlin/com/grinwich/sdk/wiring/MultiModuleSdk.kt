package com.grinwich.sdk.wiring

import com.grinwich.sdk.api.*
import com.grinwich.sdk.api.AndroidSdkLogger
import com.grinwich.sdk.contracts.*
import com.grinwich.sdk.feature.ana.AnaComponent
import com.grinwich.sdk.feature.ana.DaggerAnaComponent
import com.grinwich.sdk.feature.auth.AuthComponent
import com.grinwich.sdk.feature.auth.DaggerAuthComponent
import com.grinwich.sdk.feature.core.DaggerCoreComponent
import com.grinwich.sdk.feature.enc.DaggerEncComponent
import com.grinwich.sdk.feature.enc.EncComponent
import com.grinwich.sdk.feature.stor.DaggerStorComponent
import com.grinwich.sdk.feature.stor.StorComponent
import com.grinwich.sdk.feature.syn.DaggerSynComponent

/**
 * Realistic multi-module SDK facade.
 *
 * Gradle dependency graph (simplified):
 *
 *   app
 *    └── implementation(:sdk:sdk-wiring)
 *         ├── api(:sdk:api)                        ← app sees interfaces
 *         ├── implementation(:sdk:feature-core-impl)
 *         ├── implementation(:sdk:feature-enc-impl)
 *         ├── implementation(:sdk:feature-auth-impl)
 *         ├── implementation(:sdk:feature-stor-impl)
 *         ├── implementation(:sdk:feature-ana-impl)
 *         └── implementation(:sdk:feature-syn-impl)
 *
 *   feature-auth-impl
 *    ├── api(:sdk:di-contracts)     ← CoreProvisions, EncProvisions (contracts)
 *    └── implementation(:sdk:impl-common)  ← DefaultAuthService
 *    ✘ does NOT depend on :feature-enc-impl or :feature-core-impl
 *
 * Key: feature impls depend on PROVISION INTERFACES (contracts),
 * not on other features' @Component classes (implementations).
 * Only this wiring module imports DaggerXxxComponent.
 */
object MultiModuleSdk {

    private var _logger: SdkLogger = AndroidSdkLogger()

    private var _initialized = false

    // Provision interfaces (contracts) — not concrete Component types
    private var _core: CoreProvisions? = null
    private var _enc: EncProvisions? = null
    private var _auth: AuthProvisions? = null
    private var _storage: StorProvisions? = null
    private var _analytics: AnaProvisions? = null
    private var _sync: SynProvisions? = null

    val isInitialized: Boolean get() = _initialized

    /**
     * Initialize core. Features built on demand via get<T>().
     */
    fun init(config: SdkConfig, logger: SdkLogger = AndroidSdkLogger()) {
        check(!_initialized) { "MultiModuleSdk already initialized. Call shutdown() first." }
        _logger = logger
        _core = DaggerCoreComponent.builder()
            .config(config)
            .logger(logger)
            .build()
        _initialized = true
    }

    /**
     * Resolve a service by type. Builds the feature + dependencies on demand.
     *
     * get<AuthApi>() → builds Enc (if needed) → builds Auth → returns AuthApi
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdk not initialized." }
        val core = _core!!
        val result: Any = when (clazz) {
            // --- Encryption (depends on Core) ---
            EncryptionApi::class.java -> ensureEnc(core).encryption()
            HashApi::class.java -> ensureEnc(core).hash()

            // --- Auth (depends on Core + Enc) ---
            AuthApi::class.java -> ensureAuth(core).auth()

            // --- Storage (depends on Core + Enc) ---
            StorageApi::class.java -> ensureStor(core).storage()

            // --- Analytics (depends on Core only) ---
            AnalyticsApi::class.java -> ensureAna(core).analytics()

            // --- Sync (depends on Core + Enc + Auth + Storage) ---
            SyncApi::class.java -> ensureSyn(core).sync()

            // --- Infra ---
            SdkLogger::class.java -> _logger

            else -> error("Service ${clazz.simpleName} not available.")
        }
        return result as T
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    // ============================================================
    // Lazy builders — each ensures dependencies are built first.
    // This is the only place that imports DaggerXxxComponent.
    // ============================================================

    private fun ensureEnc(core: CoreProvisions): EncProvisions {
        return _enc ?: DaggerEncComponent.builder().core(core).build().also { _enc = it }
    }

    private fun ensureAuth(core: CoreProvisions): AuthProvisions {
        val enc = ensureEnc(core)
        return _auth ?: DaggerAuthComponent.builder().core(core).enc(enc).build().also { _auth = it }
    }

    private fun ensureStor(core: CoreProvisions): StorProvisions {
        val enc = ensureEnc(core)
        return _storage ?: DaggerStorComponent.builder().core(core).enc(enc).build().also { _storage = it }
    }

    private fun ensureAna(core: CoreProvisions): AnaProvisions {
        return _analytics ?: DaggerAnaComponent.builder().core(core).build().also { _analytics = it }
    }

    private fun ensureSyn(core: CoreProvisions): SynProvisions {
        val enc = ensureEnc(core)
        val auth = ensureAuth(core)
        val stor = ensureStor(core)
        return _sync ?: DaggerSynComponent.builder()
            .core(core).enc(enc).auth(auth).storage(stor)
            .build()
            .also { _sync = it }
    }

    fun shutdown() {
        if (!_initialized) return
        _core = null; _enc = null; _auth = null
        _storage = null; _analytics = null; _sync = null
        _initialized = false
    }
}

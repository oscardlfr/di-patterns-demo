package com.grinwich.sdk.wiring

import com.grinwich.sdk.api.*
import com.grinwich.sdk.feature.observability.AndroidSdkLogger
import com.grinwich.sdk.contracts.*
import com.grinwich.sdk.feature.ana.DaggerAnaComponent
import com.grinwich.sdk.feature.auth.DaggerAuthComponent
import com.grinwich.sdk.feature.core.DaggerCoreComponent
import com.grinwich.sdk.feature.enc.DaggerEncComponent
import com.grinwich.sdk.feature.stor.DaggerStorComponent
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
 *
 * Logger persists across init/shutdown cycles — set once at first init
 * or via setLogger(), never lost on reinit.
 */
object MultiModuleSdk : MultiModuleSdkApi {

    private val lock = Any()
    private var _logger: SdkLogger = AndroidSdkLogger()

    @Volatile private var _initialized = false

    // Provision interfaces (contracts) — not concrete Component types
    @Volatile private var _core: CoreProvisions? = null
    @Volatile private var _enc: EncProvisions? = null
    @Volatile private var _auth: AuthProvisions? = null
    @Volatile private var _storage: StorProvisions? = null
    @Volatile private var _analytics: AnaProvisions? = null
    @Volatile private var _sync: SynProvisions? = null

    override val isInitialized: Boolean get() = _initialized

    /** Number of provisions currently built. Useful for verifying lazy behavior in tests. */
    override val builtProvisionCount: Int get() = listOfNotNull(_core, _enc, _auth, _storage, _analytics, _sync).size

    /** Override the default logger. Persists across init/shutdown cycles. */
    fun setLogger(logger: SdkLogger) {
        _logger = logger
    }

    /**
     * Initialize core. Features built on demand via get<T>().
     */
    override fun init(context: android.content.Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdk already initialized. Call shutdown() first." }
        _core = DaggerCoreComponent.builder()
            .config(config)
            .build()
        _initialized = true
    }

    /**
     * Resolve a service by type. Builds the feature + dependencies on demand.
     *
     * get<AuthApi>() → builds Enc (if needed) → builds Auth → returns AuthApi
     */
    override fun <T : Any> get(clazz: Class<T>): T {
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
        return checkNotNull(clazz.cast(result)) { "Cast failed for ${clazz.simpleName}" }
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    // ============================================================
    // Lazy builders — each ensures dependencies are built first.
    // This is the only place that imports DaggerXxxComponent.
    // ============================================================

    private fun ensureEnc(core: CoreProvisions): EncProvisions {
        _enc?.let { return it }
        synchronized(lock) { return _enc ?: DaggerEncComponent.builder().core(core).logger(_logger).build().also { _enc = it } }
    }

    private fun ensureAuth(core: CoreProvisions): AuthProvisions {
        _auth?.let { return it }
        synchronized(lock) {
            _auth?.let { return it }
            val enc = ensureEnc(core)
            return DaggerAuthComponent.builder().core(core).logger(_logger).enc(enc).build().also { _auth = it }
        }
    }

    private fun ensureStor(core: CoreProvisions): StorProvisions {
        _storage?.let { return it }
        synchronized(lock) {
            _storage?.let { return it }
            val enc = ensureEnc(core)
            return DaggerStorComponent.builder().core(core).logger(_logger).enc(enc).build().also { _storage = it }
        }
    }

    private fun ensureAna(core: CoreProvisions): AnaProvisions {
        _analytics?.let { return it }
        synchronized(lock) { return _analytics ?: DaggerAnaComponent.builder().core(core).logger(_logger).build().also { _analytics = it } }
    }

    private fun ensureSyn(core: CoreProvisions): SynProvisions {
        _sync?.let { return it }
        synchronized(lock) {
            _sync?.let { return it }
            val enc = ensureEnc(core)
            val auth = ensureAuth(core)
            val stor = ensureStor(core)
            return DaggerSynComponent.builder()
                .core(core).logger(_logger).enc(enc).auth(auth).storage(stor)
                .build()
                .also { _sync = it }
        }
    }

    override fun shutdown() {
        if (!_initialized) return
        synchronized(lock) {
            _core = null; _enc = null; _auth = null
            _storage = null; _analytics = null; _sync = null
            _initialized = false
        }
    }
}

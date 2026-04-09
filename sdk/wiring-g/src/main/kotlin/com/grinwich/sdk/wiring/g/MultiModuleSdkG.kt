package com.grinwich.sdk.wiring.g

import android.content.Context
import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.*
import com.grinwich.sdk.feature.ana.buildAnaProvisions
import com.grinwich.sdk.feature.auth.buildAuthProvisions
import com.grinwich.sdk.feature.observability.AndroidSdkLogger
import com.grinwich.sdk.feature.core.buildCoreProvisions
import com.grinwich.sdk.feature.enc.buildEncProvisions
import com.grinwich.sdk.feature.stor.buildStorProvisions
import com.grinwich.sdk.feature.syn.buildSynProvisions

/**
 * Pattern G: Factory Functions.
 *
 * Each feature-impl exposes a single factory function (buildXxxProvisions).
 * The DaggerXxxComponent stays internal — this module never imports it.
 *
 * vs D: no when-blocks, no DaggerXxx imports
 * vs E/E2: no registry, no FeatureEntry, no topo-sort/DFS
 *
 * Trade-off: the wiring module must know the dependency order between
 * features (same as D). For 50+ features, this becomes verbose.
 *
 * Logger persists across init/shutdown cycles — set once at first init
 * or via setLogger(), never lost on reinit.
 */
object MultiModuleSdkG : MultiModuleSdkApi {

    private val lock = Any()
    private var _logger: SdkLogger = AndroidSdkLogger()
    @Volatile private var _initialized = false

    @Volatile private var _ctxProvisions: ContextProvisions? = null
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

    override fun init(context: android.content.Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkG already initialized. Call shutdown() first." }
        val appCtx = context.applicationContext
        _ctxProvisions = object : ContextProvisions {
            override fun appContext(): Context = appCtx
        }
        _core = buildCoreProvisions(config)
        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkG not initialized." }
        val core = _core!!
        val result: Any = when (clazz) {
            EncryptionApi::class.java -> ensureEnc(core).encryption()
            HashApi::class.java -> ensureEnc(core).hash()
            AuthApi::class.java -> ensureAuth(core).auth()
            StorageApi::class.java -> ensureStor(core).storage()
            AnalyticsApi::class.java -> ensureAna(core).analytics()
            SyncApi::class.java -> ensureSyn(core).sync()
            SdkLogger::class.java -> _logger
            else -> error("Service ${clazz.simpleName} not available.")
        }
        return checkNotNull(clazz.cast(result)) { "Cast failed for ${clazz.simpleName}" }
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    // ── Lazy builders using factory functions ──
    // Key difference vs D: calls buildXxxProvisions() instead of DaggerXxxComponent.builder()

    private fun ensureEnc(core: CoreProvisions): EncProvisions {
        _enc?.let { return it }
        synchronized(lock) { return _enc ?: buildEncProvisions(core, _logger).also { _enc = it } }
    }

    private fun ensureAuth(core: CoreProvisions): AuthProvisions {
        _auth?.let { return it }
        synchronized(lock) {
            _auth?.let { return it }
            val enc = ensureEnc(core)
            return buildAuthProvisions(core, _logger, enc).also { _auth = it }
        }
    }

    private fun ensureStor(core: CoreProvisions): StorProvisions {
        _storage?.let { return it }
        synchronized(lock) {
            _storage?.let { return it }
            val enc = ensureEnc(core)
            return buildStorProvisions(core, _logger, enc, _ctxProvisions!!).also { _storage = it }
        }
    }

    private fun ensureAna(core: CoreProvisions): AnaProvisions {
        _analytics?.let { return it }
        synchronized(lock) { return _analytics ?: buildAnaProvisions(core, _logger).also { _analytics = it } }
    }

    private fun ensureSyn(core: CoreProvisions): SynProvisions {
        _sync?.let { return it }
        synchronized(lock) {
            _sync?.let { return it }
            val enc = ensureEnc(core)
            val auth = ensureAuth(core)
            val stor = ensureStor(core)
            return buildSynProvisions(core, _logger, enc, auth, stor).also { _sync = it }
        }
    }

    override fun shutdown() {
        if (!_initialized) return
        synchronized(lock) {
            _ctxProvisions = null; _core = null; _enc = null; _auth = null
            _storage = null; _analytics = null; _sync = null
            _initialized = false
        }
    }
}

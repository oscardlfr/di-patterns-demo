package com.grinwich.sdk.wiring.g

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
object MultiModuleSdkG {

    private var _logger: SdkLogger = AndroidSdkLogger()
    private var _initialized = false

    private var _core: CoreProvisions? = null
    private var _enc: EncProvisions? = null
    private var _auth: AuthProvisions? = null
    private var _storage: StorProvisions? = null
    private var _analytics: AnaProvisions? = null
    private var _sync: SynProvisions? = null

    val isInitialized: Boolean get() = _initialized

    /** Override the default logger. Persists across init/shutdown cycles. */
    fun setLogger(logger: SdkLogger) {
        _logger = logger
    }

    fun init(config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkG already initialized. Call shutdown() first." }
        _core = buildCoreProvisions(config)
        _initialized = true
    }

    fun <T : Any> get(clazz: Class<T>): T {
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

    private fun ensureEnc(core: CoreProvisions): EncProvisions =
        _enc ?: buildEncProvisions(core, _logger).also { _enc = it }

    private fun ensureAuth(core: CoreProvisions): AuthProvisions {
        val enc = ensureEnc(core)
        return _auth ?: buildAuthProvisions(core, _logger, enc).also { _auth = it }
    }

    private fun ensureStor(core: CoreProvisions): StorProvisions {
        val enc = ensureEnc(core)
        return _storage ?: buildStorProvisions(core, _logger, enc).also { _storage = it }
    }

    private fun ensureAna(core: CoreProvisions): AnaProvisions =
        _analytics ?: buildAnaProvisions(core, _logger).also { _analytics = it }

    private fun ensureSyn(core: CoreProvisions): SynProvisions {
        val enc = ensureEnc(core)
        val auth = ensureAuth(core)
        val stor = ensureStor(core)
        return _sync ?: buildSynProvisions(core, _logger, enc, auth, stor).also { _sync = it }
    }

    fun shutdown() {
        if (!_initialized) return
        _core = null; _enc = null; _auth = null
        _storage = null; _analytics = null; _sync = null
        _initialized = false
    }
}

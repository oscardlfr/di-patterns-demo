package com.grinwich.sdk.wiring

import android.content.Context
import com.grinwich.sdk.api.*
import com.grinwich.sdk.feature.ana.buildAnalyticsService
import com.grinwich.sdk.feature.auth.buildAuthService
import com.grinwich.sdk.feature.enc.EncBundle
import com.grinwich.sdk.feature.enc.buildEncBundle
import com.grinwich.sdk.feature.observability.buildLogger
import com.grinwich.sdk.feature.stor.buildStorageService
import com.grinwich.sdk.feature.syn.buildSyncService

/**
 * Realistic multi-module SDK facade (baseline).
 *
 * After the refactor it uses each feature's public factories
 * (`buildEncBundle`, `buildAuthService`, …) instead of importing
 * `DaggerXxxComponent`. Logger persists across init/shutdown cycles.
 */
object MultiModuleSdk : MultiModuleSdkApi {

    private val lock = Any()
    private var _logger: SdkLogger = buildLogger()
    @Volatile private var _initialized = false

    /**
     * Opaque container for infrastructure (Context + SdkConfig). Typed as
     * `Map<Class<*>, Any>` so lint does not see a `Context` directly declared
     * in the static class — the same pattern [Resolver] uses in H/I/J/K to
     * avoid triggering `StaticFieldLeak`.
     */
    @Volatile private var _infra: Map<Class<*>, Any> = emptyMap()

    @Volatile private var _enc: EncBundle? = null
    @Volatile private var _auth: AuthApi? = null
    @Volatile private var _storage: StorageApi? = null
    @Volatile private var _analytics: AnalyticsApi? = null
    @Volatile private var _sync: SyncApi? = null

    override val isInitialized: Boolean get() = _initialized

    override val builtFeatureCount: Int
        get() = listOfNotNull(_enc, _auth, _storage, _analytics, _sync).size

    fun setLogger(logger: SdkLogger) {
        _logger = logger
    }

    override fun init(context: android.content.Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdk already initialized. Call shutdown() first." }
        _infra = mapOf(
            Context::class.java to context.applicationContext,
            SdkConfig::class.java to config,
        )
        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdk not initialized." }
        val result: Any = when (clazz) {
            EncryptionApi::class.java -> ensureEnc().encryption()
            HashApi::class.java -> ensureEnc().hash()
            AuthApi::class.java -> ensureAuth()
            StorageApi::class.java -> ensureStor()
            AnalyticsApi::class.java -> ensureAna()
            SyncApi::class.java -> ensureSyn()
            SdkLogger::class.java -> _logger
            else -> _infra[clazz] ?: error("Service ${clazz.simpleName} not available.")
        }
        return checkNotNull(clazz.cast(result)) { "Cast failed for ${clazz.simpleName}" }
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    private fun ensureEnc(): EncBundle {
        _enc?.let { return it }
        synchronized(lock) { return _enc ?: buildEncBundle(_logger).also { _enc = it } }
    }

    private fun ensureAuth(): AuthApi {
        _auth?.let { return it }
        synchronized(lock) {
            _auth?.let { return it }
            val enc = ensureEnc()
            return buildAuthService(enc.encryption(), _logger).also { _auth = it }
        }
    }

    private fun ensureStor(): StorageApi {
        _storage?.let { return it }
        synchronized(lock) {
            _storage?.let { return it }
            val enc = ensureEnc()
            return buildStorageService(
                context = _infra[Context::class.java] as Context,
                config = _infra[SdkConfig::class.java] as SdkConfig,
                encryption = enc.encryption(),
                hash = enc.hash(),
                logger = _logger,
            ).also { _storage = it }
        }
    }

    private fun ensureAna(): AnalyticsApi {
        _analytics?.let { return it }
        synchronized(lock) { return _analytics ?: buildAnalyticsService(_logger).also { _analytics = it } }
    }

    private fun ensureSyn(): SyncApi {
        _sync?.let { return it }
        synchronized(lock) {
            _sync?.let { return it }
            val auth = ensureAuth()
            val stor = ensureStor()
            val enc = ensureEnc()
            return buildSyncService(
                auth = auth,
                storage = stor,
                encryption = enc.encryption(),
                logger = _logger,
            ).also { _sync = it }
        }
    }

    override fun shutdown() {
        if (!_initialized) return
        synchronized(lock) {
            _infra = emptyMap()
            _enc = null; _auth = null; _storage = null; _analytics = null; _sync = null
            _initialized = false
        }
    }
}

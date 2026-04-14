package com.grinwich.sdk.wiring.o2

import android.content.Context
import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.LazyCreationTracker
import com.grinwich.sdk.feature.observability.AndroidSdkLogger
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory

/**
 * Pattern O2: Metro Lazy — compile-time aggregation with lazy singletons.
 *
 * Same as Pattern O but graph accessors return [Lazy]<T>. The graph/component
 * is created at init(), but singletons are NOT instantiated until first access.
 * [LazyCreationTracker] counts how many features have been materialized.
 */
@DependencyGraph(AppScope::class)
interface SdkGraph {
    val context: Context
    val encryption: Lazy<EncryptionApi>
    val hashApi: Lazy<HashApi>
    val auth: Lazy<AuthApi>
    val storage: Lazy<StorageApi>
    val analytics: Lazy<AnalyticsApi>
    val sync: Lazy<SyncApi>

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(
            @Provides context: Context,
            @Provides config: SdkConfig,
            @Provides logger: SdkLogger,
            @Provides storageBackend: StorageBackend,
        ): SdkGraph
    }
}

object MultiModuleSdkO2 : MultiModuleSdkApi {

    private var _graph: SdkGraph? = null
    private var _initialized = false
    private var _tracker: LazyCreationTracker.Instance? = null

    // Persistent — survive shutdown/reinit cycles (intentional: ApplicationContext-level singleton)
    private var _logger: SdkLogger = AndroidSdkLogger()

    override val isInitialized: Boolean get() = _initialized

    /**
     * Lazy Metro: graph is created at init but singletons are NOT instantiated
     * until first access via Lazy<T>.value. builtProvisionCount reads from
     * [LazyCreationTracker] which is incremented by @Provides methods.
     */
    override val builtProvisionCount: Int get() = _tracker?.count ?: 0

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkO2 already initialized. Call shutdown() first." }

        _tracker = LazyCreationTracker.activate()
        val appCtx = context.applicationContext
        _graph = createGraphFactory<SdkGraph.Factory>().create(
            context = appCtx,
            config = config,
            logger = _logger,
            storageBackend = config.storageBackend,
        )

        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkO2 not initialized." }
        val graph = _graph ?: error("graph is null")
        val instance: Any = when (clazz) {
            EncryptionApi::class.java -> graph.encryption.value
            HashApi::class.java -> graph.hashApi.value
            AuthApi::class.java -> graph.auth.value
            StorageApi::class.java -> graph.storage.value
            AnalyticsApi::class.java -> graph.analytics.value
            SyncApi::class.java -> graph.sync.value
            SdkLogger::class.java -> _logger
            Context::class.java -> graph.context
            else -> error("No binding for ${clazz.simpleName} in SdkGraph")
        }
        return checkNotNull(clazz.cast(instance)) { "Cast failed for ${clazz.simpleName}" }
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    override fun shutdown() {
        if (!_initialized) return
        LazyCreationTracker.deactivate()
        _graph = null
        _tracker?.clear()
        _tracker = null
        _initialized = false
    }
}

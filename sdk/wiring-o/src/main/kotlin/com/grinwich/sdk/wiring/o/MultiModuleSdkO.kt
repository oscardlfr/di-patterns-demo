package com.grinwich.sdk.wiring.o

import android.content.Context
import com.grinwich.sdk.api.*
import com.grinwich.sdk.feature.observability.AndroidSdkLogger
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory

/**
 * Pattern O: Metro — compile-time aggregation DI.
 *
 * Metro's compiler plugin aggregates all @ContributesTo bindings from
 * feature-impl modules into [SdkGraph] at compile time. Zero runtime
 * discovery, zero ServiceLoader, zero Resolver.
 *
 * Key comparison: compile-time graph assembly vs runtime DI (Koin, Resolver).
 * Metro creates the full graph eagerly at init — no laziness.
 */
@DependencyGraph(AppScope::class)
interface SdkGraph {
    val context: Context
    val encryption: EncryptionApi
    val hashApi: HashApi
    val auth: AuthApi
    val storage: StorageApi
    val analytics: AnalyticsApi
    val sync: SyncApi

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

object MultiModuleSdkO : MultiModuleSdkApi {

    private var _graph: SdkGraph? = null
    private var _initialized = false

    // Persistent — survive shutdown/reinit cycles (intentional: ApplicationContext-level singleton)
    private var _logger: SdkLogger = AndroidSdkLogger()

    override val isInitialized: Boolean get() = _initialized

    /**
     * Metro creates all bindings at graph construction. builtProvisionCount
     * reflects feature groups (5 business features). Always 5 after init,
     * 0 after shutdown — this IS the data point: eager compile-time DI.
     */
    override val builtProvisionCount: Int get() = if (_initialized) 5 else 0

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkO already initialized. Call shutdown() first." }

        val appCtx = context.applicationContext
        _graph = createGraphFactory<SdkGraph.Factory>().create(
            context = appCtx,
            config = config,
            logger = _logger,
            storageBackend = config.storageBackend,
        )

        _initialized = true
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkO not initialized." }
        val graph = _graph ?: error("graph is null")
        return when (clazz) {
            EncryptionApi::class.java -> graph.encryption
            HashApi::class.java -> graph.hashApi
            AuthApi::class.java -> graph.auth
            StorageApi::class.java -> graph.storage
            AnalyticsApi::class.java -> graph.analytics
            SyncApi::class.java -> graph.sync
            SdkLogger::class.java -> _logger
            Context::class.java -> graph.context
            else -> error("No binding for ${clazz.simpleName} in SdkGraph")
        } as T
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    override fun shutdown() {
        if (!_initialized) return
        _graph = null
        _initialized = false
    }
}

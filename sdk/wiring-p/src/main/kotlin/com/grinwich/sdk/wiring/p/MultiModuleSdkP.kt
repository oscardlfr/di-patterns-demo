package com.grinwich.sdk.wiring.p

import android.content.Context
import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.SdkScope
import com.grinwich.sdk.feature.observability.AndroidSdkLogger
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Pattern P: kotlin-inject-anvil — compile-time multi-module wiring.
 *
 * [SdkComponent] uses @MergeComponent to aggregate all @ContributesTo(SdkScope)
 * interfaces from feature-impl modules. KSP generates a merged component that
 * includes all contributed bindings.
 *
 * Eliminates FeatureProvider, ServiceLoader, META-INF, and the Resolver entirely.
 * Key comparison: KSP-based aggregation (kotlin-inject) vs compiler-plugin (Metro).
 */
@MergeComponent(SdkScope::class)
@SingleIn(SdkScope::class)
abstract class SdkComponent(
    @get:Provides val context: Context,
    @get:Provides val config: SdkConfig,
    @get:Provides val logger: SdkLogger,
    @get:Provides val storageBackend: StorageBackend,
) {
    abstract val encryption: EncryptionApi
    abstract val hashApi: HashApi
    abstract val auth: AuthApi
    abstract val storage: StorageApi
    abstract val analytics: AnalyticsApi
    abstract val sync: SyncApi
}

object MultiModuleSdkP : MultiModuleSdkApi {

    private var _component: SdkComponent? = null
    private var _initialized = false

    // Persistent — survive shutdown/reinit cycles (intentional: ApplicationContext-level singleton)
    private var _logger: SdkLogger = AndroidSdkLogger()

    override val isInitialized: Boolean get() = _initialized

    /**
     * kotlin-inject-anvil creates all bindings at component construction.
     * builtProvisionCount reflects feature groups (5 business features).
     */
    override val builtProvisionCount: Int get() = if (_initialized) 5 else 0

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkP already initialized. Call shutdown() first." }

        val appCtx = context.applicationContext
        _component = SdkComponent::class.create(
            contextDelegate = appCtx,
            configDelegate = config,
            loggerDelegate = _logger,
            storageBackendDelegate = config.storageBackend,
        )

        _initialized = true
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkP not initialized." }
        val component = _component ?: error("component is null")
        return when (clazz) {
            EncryptionApi::class.java -> component.encryption
            HashApi::class.java -> component.hashApi
            AuthApi::class.java -> component.auth
            StorageApi::class.java -> component.storage
            AnalyticsApi::class.java -> component.analytics
            SyncApi::class.java -> component.sync
            SdkLogger::class.java -> _logger
            Context::class.java -> component.context
            else -> error("No binding for ${clazz.simpleName} in SdkComponent")
        } as T
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    override fun shutdown() {
        if (!_initialized) return
        _component = null
        _initialized = false
    }
}

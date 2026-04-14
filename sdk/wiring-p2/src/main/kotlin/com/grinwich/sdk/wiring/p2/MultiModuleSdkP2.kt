package com.grinwich.sdk.wiring.p2

import android.content.Context
import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.LazyCreationTracker
import com.grinwich.sdk.contracts.SdkScope
import com.grinwich.sdk.feature.observability.AndroidSdkLogger
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Pattern P2: kotlin-inject-anvil Lazy — compile-time wiring, lazy singletons.
 *
 * Same @MergeComponent as Pattern P, but [builtProvisionCount] tracks actual
 * singleton creation via [LazyCreationTracker] instead of hardcoding to 5.
 * With @SingleIn scoping, kotlin-inject creates singletons lazily on first access.
 * This variant proves that compile-time DI can be lazy when properly scoped.
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

object MultiModuleSdkP2 : MultiModuleSdkApi {

    private var _component: SdkComponent? = null
    private var _initialized = false
    private var _tracker: LazyCreationTracker.Instance? = null

    // Persistent — survive shutdown/reinit cycles (intentional: ApplicationContext-level singleton)
    private var _logger: SdkLogger = AndroidSdkLogger()

    override val isInitialized: Boolean get() = _initialized

    /** Tracks actual singleton creation — NOT hardcoded like P's builtProvisionCount. */
    override val builtProvisionCount: Int get() = _tracker?.count ?: 0

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkP2 already initialized. Call shutdown() first." }

        val appCtx = context.applicationContext
        _tracker = LazyCreationTracker.activate()
        _component = SdkComponent::class.create(
            contextDelegate = appCtx,
            configDelegate = config,
            loggerDelegate = _logger,
            storageBackendDelegate = config.storageBackend,
        )

        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkP2 not initialized." }
        val component = _component ?: error("component is null")
        val instance: Any = when (clazz) {
            EncryptionApi::class.java -> component.encryption
            HashApi::class.java -> component.hashApi
            AuthApi::class.java -> component.auth
            StorageApi::class.java -> component.storage
            AnalyticsApi::class.java -> component.analytics
            SyncApi::class.java -> component.sync
            SdkLogger::class.java -> _logger
            Context::class.java -> component.context
            else -> error("No binding for ${clazz.simpleName} in SdkComponent")
        }
        return checkNotNull(clazz.cast(instance)) { "Cast failed for ${clazz.simpleName}" }
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    override fun shutdown() {
        if (!_initialized) return
        LazyCreationTracker.deactivate()
        _component = null
        _tracker?.clear()
        _tracker = null
        _initialized = false
    }
}

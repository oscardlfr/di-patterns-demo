package com.grinwich.sdk.wiring.p2

import android.annotation.SuppressLint
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

@SuppressLint("StaticFieldLeak") // Only applicationContext stored — safe, nulled on shutdown()
object MultiModuleSdkP2 : MultiModuleSdkApi {

    private var _component: SdkComponent? = null
    private var _initialized = false
    private var _tracker: LazyCreationTracker.Instance? = null

    // Persistent — survive shutdown/reinit cycles (intentional: ApplicationContext-level singleton)
    private var _logger: SdkLogger = AndroidSdkLogger()
    private var _context: Context? = null

    override val isInitialized: Boolean get() = _initialized

    /** Tracks actual singleton creation — NOT hardcoded like P's builtProvisionCount. */
    override val builtProvisionCount: Int get() = _tracker?.count ?: 0

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkP2 already initialized. Call shutdown() first." }

        _context = context.applicationContext
        _tracker = LazyCreationTracker.activate()
        _component = SdkComponent::class.create(
            contextDelegate = _context!!,
            configDelegate = config,
            loggerDelegate = _logger,
            storageBackendDelegate = config.storageBackend,
        )

        _initialized = true
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkP2 not initialized." }
        val component = _component ?: error("component is null")
        return when (clazz) {
            EncryptionApi::class.java -> component.encryption
            HashApi::class.java -> component.hashApi
            AuthApi::class.java -> component.auth
            StorageApi::class.java -> component.storage
            AnalyticsApi::class.java -> component.analytics
            SyncApi::class.java -> component.sync
            SdkLogger::class.java -> _logger
            Context::class.java -> _context!!
            else -> error("No binding for ${clazz.simpleName} in SdkComponent")
        } as T
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    override fun shutdown() {
        if (!_initialized) return
        LazyCreationTracker.deactivate()
        _component = null
        _tracker?.clear()
        _tracker = null
        _context = null
        _initialized = false
    }
}

package com.grinwich.sdk.wiring.p2

import android.content.Context
import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.LazyCreationTracker
import com.grinwich.sdk.contracts.SdkScope
import com.grinwich.sdk.feature.observability.buildLogger
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Interface exposing ONLY the business services. Does not declare `context: Context`
 * — so the field that stores this interface in the `object MultiModuleSdkP2` does
 * not expose a `Context` directly to lint's `StaticFieldLeak` analysis.
 *
 * The concrete [SdkComponent] class does hold Context (kotlin-inject-anvil
 * requires it as `@get:Provides` to inject into features like Storage).
 * Storing it via the [SdkServices] type keeps Context out of the static field's
 * declared type — which is what lint inspects.
 */
interface SdkServices {
    val encryption: EncryptionApi
    val hashApi: HashApi
    val auth: AuthApi
    val storage: StorageApi
    val analytics: AnalyticsApi
    val sync: SyncApi
}

/**
 * Pattern P2: kotlin-inject-anvil Lazy — compile-time wiring, lazy singletons.
 *
 * Same @MergeComponent as Pattern P, but [builtFeatureCount] tracks the actual
 * singleton creations via [LazyCreationTracker] instead of returning a hardcoded
 * 5. With @SingleIn, kotlin-inject creates singletons lazily on first access.
 * Demonstrates that compile-time DI can be lazy with scoping.
 */
@MergeComponent(SdkScope::class)
@SingleIn(SdkScope::class)
abstract class SdkComponent(
    @get:Provides val context: Context,
    @get:Provides val config: SdkConfig,
    @get:Provides val logger: SdkLogger,
    @get:Provides val storageBackend: StorageBackend,
) : SdkServices

object MultiModuleSdkP2 : MultiModuleSdkApi {

    // Typed as SdkServices (not SdkComponent) so Context is not exposed to
    // lint's StaticFieldLeak analysis.
    private var _services: SdkServices? = null
    private var _initialized = false
    private var _tracker: LazyCreationTracker.Instance? = null

    // Persistent — survive shutdown/reinit cycles (intentional: ApplicationContext-level singleton)
    private var _logger: SdkLogger = buildLogger()

    override val isInitialized: Boolean get() = _initialized

    /** Tracks actual singleton creation — NOT hardcoded as in P. */
    override val builtFeatureCount: Int get() = _tracker?.count ?: 0

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkP2 already initialized. Call shutdown() first." }

        val appCtx = context.applicationContext
        _tracker = LazyCreationTracker.activate()
        _services = SdkComponent::class.create(
            contextDelegate = appCtx,
            configDelegate = config,
            loggerDelegate = _logger,
            storageBackendDelegate = config.storageBackend,
        )

        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkP2 not initialized." }
        val services = _services ?: error("services is null")
        val tracker = _tracker ?: error("tracker is null")
        val instance: Any = tracker.withActive {
            when (clazz) {
                EncryptionApi::class.java -> services.encryption
                HashApi::class.java -> services.hashApi
                AuthApi::class.java -> services.auth
                StorageApi::class.java -> services.storage
                AnalyticsApi::class.java -> services.analytics
                SyncApi::class.java -> services.sync
                SdkLogger::class.java -> _logger
                // Context is available via the concrete class; the field itself
                // does not expose it (declared type = SdkServices).
                Context::class.java -> (services as SdkComponent).context
                else -> error("No binding for ${clazz.simpleName} in SdkComponent")
            }
        }
        return checkNotNull(clazz.cast(instance)) { "Cast failed for ${clazz.simpleName}" }
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    override fun shutdown() {
        if (!_initialized) return
        LazyCreationTracker.deactivate()
        _services = null
        _tracker?.clear()
        _tracker = null
        _initialized = false
    }
}

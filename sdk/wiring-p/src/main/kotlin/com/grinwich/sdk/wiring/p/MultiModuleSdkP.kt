package com.grinwich.sdk.wiring.p

import android.content.Context
import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.SdkScope
import com.grinwich.sdk.feature.observability.buildLogger
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Interface exposed to the static field — declares ONLY the business services.
 * Does not mention `Context`, so the field type on the `object` does not trigger
 * lint's `StaticFieldLeak` analysis. The concrete [SdkComponent] class does hold
 * Context (kotlin-inject-anvil requires it as `@get:Provides`).
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
 * Pattern P: kotlin-inject-anvil — compile-time multi-module wiring.
 *
 * [SdkComponent] uses @MergeComponent to aggregate all @ContributesTo(SdkScope)
 * interfaces from the feature-impl modules. KSP generates a merged component
 * containing all contributed bindings.
 *
 * Eliminates FeatureProvider, ServiceLoader, META-INF and the Resolver.
 */
@MergeComponent(SdkScope::class)
@SingleIn(SdkScope::class)
abstract class SdkComponent(
    @get:Provides val context: Context,
    @get:Provides val config: SdkConfig,
    @get:Provides val logger: SdkLogger,
    @get:Provides val storageBackend: StorageBackend,
) : SdkServices

object MultiModuleSdkP : MultiModuleSdkApi {

    private var _services: SdkServices? = null
    private var _initialized = false

    // Persistent — survive shutdown/reinit cycles (intentional: ApplicationContext-level singleton)
    private var _logger: SdkLogger = buildLogger()

    override val isInitialized: Boolean get() = _initialized

    /**
     * kotlin-inject-anvil materializes all bindings when the component is built.
     * builtFeatureCount reflects the 5 business feature groups.
     */
    override val builtFeatureCount: Int get() = if (_initialized) 5 else 0

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkP already initialized. Call shutdown() first." }

        val appCtx = context.applicationContext
        _services = SdkComponent::class.create(
            contextDelegate = appCtx,
            configDelegate = config,
            loggerDelegate = _logger,
            storageBackendDelegate = config.storageBackend,
        )

        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkP not initialized." }
        val services = _services ?: error("services is null")
        val instance: Any = when (clazz) {
            EncryptionApi::class.java -> services.encryption
            HashApi::class.java -> services.hashApi
            AuthApi::class.java -> services.auth
            StorageApi::class.java -> services.storage
            AnalyticsApi::class.java -> services.analytics
            SyncApi::class.java -> services.sync
            SdkLogger::class.java -> _logger
            Context::class.java -> (services as SdkComponent).context
            else -> error("No binding for ${clazz.simpleName} in SdkComponent")
        }
        return checkNotNull(clazz.cast(instance)) { "Cast failed for ${clazz.simpleName}" }
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    override fun shutdown() {
        if (!_initialized) return
        _services = null
        _initialized = false
    }
}

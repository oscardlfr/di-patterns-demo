package com.grinwich.sdk.wiring.q

import android.content.Context
import com.grinwich.sdk.api.*
import com.grinwich.sdk.feature.ana.HiltAnaModule
import com.grinwich.sdk.feature.auth.HiltAuthModule
import com.grinwich.sdk.feature.enc.HiltEncModule
import com.grinwich.sdk.feature.observability.AndroidSdkLogger
import com.grinwich.sdk.feature.stor.HiltStorModule
import com.grinwich.sdk.feature.syn.HiltSynModule
import dagger.BindsInstance
import dagger.Component
import javax.inject.Singleton

/**
 * Pattern Q: Hilt-style Dagger — compile-time module aggregation.
 *
 * Each feature-impl defines a @Module @InstallIn(SingletonComponent) following
 * Hilt conventions. In a real Hilt app, these would be auto-discovered via
 * @HiltAndroidApp. Here, we include them explicitly in a @Component to
 * benchmark Dagger's compile-time DI without requiring Hilt's Application lifecycle.
 *
 * Key comparison: Dagger @Component with pre-wired modules (Hilt-style)
 * vs Metro @DependencyGraph vs kotlin-inject-anvil @MergeComponent.
 * All three are compile-time DI — the difference is the code generator.
 */
@Singleton
@Component(
    modules = [
        HiltEncModule::class,
        HiltAuthModule::class,
        HiltStorModule::class,
        HiltAnaModule::class,
        HiltSynModule::class,
    ],
)
interface SdkComponent {
    fun context(): Context
    fun encryption(): EncryptionApi
    fun hash(): HashApi
    fun auth(): AuthApi
    fun storage(): StorageApi
    fun analytics(): AnalyticsApi
    fun sync(): SyncApi

    @Component.Factory
    interface Factory {
        fun create(
            @BindsInstance context: Context,
            @BindsInstance config: SdkConfig,
            @BindsInstance logger: SdkLogger,
            @BindsInstance storageBackend: StorageBackend,
        ): SdkComponent
    }
}

object MultiModuleSdkQ : MultiModuleSdkApi {

    private var _component: SdkComponent? = null
    private var _initialized = false

    // Persistent — survive shutdown/reinit cycles (intentional: ApplicationContext-level singleton)
    private var _logger: SdkLogger = AndroidSdkLogger()

    override val isInitialized: Boolean get() = _initialized

    /**
     * Dagger creates all @Singleton bindings lazily on first access, but the
     * component itself is fully wired at creation. builtProvisionCount = 5
     * after init (all feature modules included at compile time).
     */
    override val builtProvisionCount: Int get() = if (_initialized) 5 else 0

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkQ already initialized. Call shutdown() first." }

        val appCtx = context.applicationContext
        _component = DaggerSdkComponent.factory().create(
            context = appCtx,
            config = config,
            logger = _logger,
            storageBackend = config.storageBackend,
        )

        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkQ not initialized." }
        val component = _component ?: error("component is null")
        val instance: Any = when (clazz) {
            EncryptionApi::class.java -> component.encryption()
            HashApi::class.java -> component.hash()
            AuthApi::class.java -> component.auth()
            StorageApi::class.java -> component.storage()
            AnalyticsApi::class.java -> component.analytics()
            SyncApi::class.java -> component.sync()
            SdkLogger::class.java -> _logger
            Context::class.java -> component.context()
            else -> error("No binding for ${clazz.simpleName} in SdkComponent")
        }
        return checkNotNull(clazz.cast(instance)) { "Cast failed for ${clazz.simpleName}" }
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    override fun shutdown() {
        if (!_initialized) return
        _component = null
        _initialized = false
    }
}

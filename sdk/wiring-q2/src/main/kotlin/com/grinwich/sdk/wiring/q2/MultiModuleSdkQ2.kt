package com.grinwich.sdk.wiring.q2

import android.content.Context
import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.LazyCreationTracker
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
 * Pattern Q2: Hilt-style Dagger Lazy — compile-time module aggregation with lazy singletons.
 *
 * Same as Pattern Q but component provision methods return [dagger.Lazy]<T>.
 * The component is created at init(), but singletons are NOT instantiated
 * until first access via Lazy.get(). [LazyCreationTracker] counts how many
 * features have been materialized.
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
    fun encryption(): dagger.Lazy<EncryptionApi>
    fun hash(): dagger.Lazy<HashApi>
    fun auth(): dagger.Lazy<AuthApi>
    fun storage(): dagger.Lazy<StorageApi>
    fun analytics(): dagger.Lazy<AnalyticsApi>
    fun sync(): dagger.Lazy<SyncApi>

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

object MultiModuleSdkQ2 : MultiModuleSdkApi {

    private var _component: SdkComponent? = null
    private var _initialized = false
    private var _tracker: LazyCreationTracker.Instance? = null

    // Persistent — survive shutdown/reinit cycles (intentional: ApplicationContext-level singleton)
    private var _logger: SdkLogger = AndroidSdkLogger()

    override val isInitialized: Boolean get() = _initialized

    /**
     * Lazy Dagger: component is created at init but @Singleton bindings are
     * NOT instantiated until first access via dagger.Lazy.get().
     * builtProvisionCount reads from [LazyCreationTracker].
     */
    override val builtProvisionCount: Int get() = _tracker?.count ?: 0

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkQ2 already initialized. Call shutdown() first." }

        _tracker = LazyCreationTracker.activate()
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
        check(_initialized) { "MultiModuleSdkQ2 not initialized." }
        val component = _component ?: error("component is null")
        val instance: Any = when (clazz) {
            EncryptionApi::class.java -> component.encryption().get()
            HashApi::class.java -> component.hash().get()
            AuthApi::class.java -> component.auth().get()
            StorageApi::class.java -> component.storage().get()
            AnalyticsApi::class.java -> component.analytics().get()
            SyncApi::class.java -> component.sync().get()
            SdkLogger::class.java -> _logger
            Context::class.java -> component.context()
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

package com.grinwich.sdk.wiring.l

import android.content.Context
import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.koin.CreationTracker
import com.grinwich.sdk.contracts.koin.KoinFeatureProvider
import com.grinwich.sdk.feature.observability.AndroidSdkLogger
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.ServiceLoader
import kotlin.reflect.KClass

/**
 * Pattern L: Koin + ServiceLoader (Eager Modules).
 *
 * ServiceLoader discovers KoinFeatureProvider instances.
 * All discovered Koin modules composed into a single koinApplication at init().
 * Koin's standard single{} provides lazy singleton behavior.
 *
 * Key comparison: Koin as BOTH resolver AND DI framework
 * vs Pattern H's custom Resolver + Dagger @Component.
 */
object MultiModuleSdkL : MultiModuleSdkApi {

    private var _koinApp: KoinApplication? = null
    private var _initialized = false
    private var _tracker: CreationTracker? = null

    // Persistent — survive shutdown/reinit cycles (intentional: ApplicationContext-level singleton)
    private var _logger: SdkLogger = AndroidSdkLogger()

    override val isInitialized: Boolean get() = _initialized

    /** Number of non-persistent feature groups that have created singletons. */
    override val builtProvisionCount: Int get() = _tracker?.count ?: 0

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkL already initialized. Call shutdown() first." }

        val appCtx = context.applicationContext
        val tracker = CreationTracker()
        _tracker = tracker

        // Foundation module — captures local vals for type safety
        val foundation = module {
            single<Context> { appCtx }
            single<SdkConfig> { config }
            single<StorageBackend> { config.storageBackend }
            single<SdkLogger> { _logger }
            single<CreationTracker> { tracker }
        }

        // Discover business feature providers
        val providers = ServiceLoader.load(KoinFeatureProvider::class.java).toList()

        // Compose ALL modules eagerly
        val featureModules = providers.map { it.module() }
        _koinApp = koinApplication {
            modules(listOf(foundation) + featureModules)
        }

        _initialized = true
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkL not initialized." }
        return _koinApp!!.koin.get(clazz.kotlin as KClass<Any>) as T
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    override fun shutdown() {
        if (!_initialized) return
        _koinApp?.close()
        _koinApp = null
        _tracker?.clear()
        _tracker = null
        _initialized = false
    }
}

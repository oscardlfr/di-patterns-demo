package com.grinwich.sdk.wiring.n

import android.content.Context
import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.koin.CreationTracker
import com.grinwich.sdk.contracts.koin.KoinFeatureProvider
import com.grinwich.sdk.feature.observability.AndroidSdkLogger
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.reflect.KClass

/**
 * Pattern N: sweet-spi + Koin (Eager Modules).
 *
 * Identical to Pattern L except discovery uses sweet-spi's ServiceLoader
 * instead of java.util.ServiceLoader. On JVM, sweet-spi delegates to
 * java.util.ServiceLoader internally, reading the same META-INF/services files.
 *
 * Key comparison: sweet-spi discovery overhead vs raw ServiceLoader.
 * On non-JVM targets (Native/WASM), sweet-spi uses @EagerInitialization —
 * this benchmark measures the JVM path.
 */
object MultiModuleSdkN : MultiModuleSdkApi {

    private var _koinApp: KoinApplication? = null
    private var _initialized = false
    private var _tracker: CreationTracker? = null

    // Persistent — survive shutdown/reinit cycles (intentional: ApplicationContext-level singleton)
    private var _logger: SdkLogger = AndroidSdkLogger()

    override val isInitialized: Boolean get() = _initialized

    override val builtProvisionCount: Int get() = _tracker?.count ?: 0

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkN already initialized. Call shutdown() first." }

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

        // sweet-spi discovery — on JVM delegates to java.util.ServiceLoader
        // reading the same META-INF/services/com.grinwich.sdk.contracts.koin.KoinFeatureProvider
        val providers = dev.whyoleg.sweetspi.ServiceLoader.load<KoinFeatureProvider>()

        val featureModules = providers.map { it.module() }
        _koinApp = koinApplication {
            modules(listOf(foundation) + featureModules)
        }

        _initialized = true
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkN not initialized." }
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

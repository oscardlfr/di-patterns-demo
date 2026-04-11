package com.grinwich.sdk.wiring.m

import android.content.Context
import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.koin.CreationTracker
import com.grinwich.sdk.contracts.koin.KoinFeatureProvider
import com.grinwich.sdk.feature.observability.AndroidSdkLogger
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Pattern M: Koin + ServiceLoader (Lazy loadModules).
 *
 * Same discovery as Pattern L. But only the foundation module is loaded at init().
 * Feature modules are loaded on demand via koin.loadModules() when a service
 * from that feature is first requested through get().
 *
 * On get<T>(): find which KoinFeatureProvider owns T → load its requiredServices
 * providers first (cascade) → load its module → resolve T from Koin.
 *
 * Key comparison: Does lazy loadModules provide meaningful advantage over
 * Pattern L's eager module registration? How does cascade compare to Resolver DFS?
 */
object MultiModuleSdkM : MultiModuleSdkApi {

    private var _koinApp: KoinApplication? = null
    private var _initialized = false
    private var _providers = emptyList<KoinFeatureProvider>()
    private val _serviceToProvider = mutableMapOf<Class<*>, KoinFeatureProvider>()
    private val _loadedProviders: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val loadLock = Any()
    private var _tracker: CreationTracker? = null

    // Persistent — survive shutdown/reinit cycles (intentional: ApplicationContext-level singleton)
    private var _logger: SdkLogger = AndroidSdkLogger()

    override val isInitialized: Boolean get() = _initialized

    /** Number of non-persistent feature modules loaded via loadModules(). */
    override val builtProvisionCount: Int get() = _loadedProviders.size

    override fun init(context: Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkM already initialized. Call shutdown() first." }

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

        // Discover (but don't load) business feature providers
        _providers = ServiceLoader.load(KoinFeatureProvider::class.java).toList()

        _serviceToProvider.clear()
        for (provider in _providers) {
            for (svc in provider.services) {
                _serviceToProvider[svc] = provider
            }
        }

        // Only foundation loaded at init
        _koinApp = koinApplication {
            modules(foundation)
        }

        _loadedProviders.clear()
        _initialized = true
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkM not initialized." }

        // Ensure the provider for this service is loaded (cascade)
        ensureLoaded(clazz)

        return _koinApp!!.koin.get(clazz.kotlin as KClass<Any>) as T
    }

    /**
     * Synchronized cascade: load required dependencies first, then this provider.
     * Double-check pattern prevents double-loading under concurrent access.
     */
    private fun ensureLoaded(serviceClass: Class<*>) {
        val provider = _serviceToProvider[serviceClass] ?: return // foundation service
        if (provider.featureName in _loadedProviders) return // fast path

        synchronized(loadLock) {
            if (provider.featureName in _loadedProviders) return // double-check
            if (!_initialized) return // Guard against race with shutdown

            // Cascade: load required dependencies first
            for (requiredService in provider.requiredServices) {
                ensureLoaded(requiredService)
            }

            // Load this provider's module into the running Koin instance
            _koinApp!!.koin.loadModules(listOf(provider.module()))
            _loadedProviders.add(provider.featureName)
        }
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    override fun shutdown() {
        if (!_initialized) return
        synchronized(loadLock) {
            _koinApp?.close()
            _koinApp = null
            _loadedProviders.clear()
            _serviceToProvider.clear()
            _providers = emptyList()
            _tracker?.clear()
            _tracker = null
            _initialized = false
        }
    }
}

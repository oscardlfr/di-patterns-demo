package com.grinwich.sdk.wiring.m

import android.content.Context
import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.koin.CreationTracker
import com.grinwich.sdk.contracts.koin.KoinFeatureProvider
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock

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

    /**
     * Serialises lifecycle transitions (init/shutdown) with `writeLock` and lets
     * concurrent `get()` calls proceed in parallel with `readLock`. Guarantees
     * no resolver reads the Koin container after it has been closed — the
     * `loadLock` above keeps multiple readers from duplicating `loadModules()`,
     * while `rwLock` keeps `shutdown()` from closing the container mid-resolve.
     */
    private val rwLock = ReentrantReadWriteLock()

    private var _tracker: CreationTracker? = null

    override val isInitialized: Boolean get() = _initialized

    /**
     * Count of feature modules loaded via loadModules(). Excludes persistent
     * providers (infrastructure such as Observability) which are loaded on
     * demand but are not business features.
     */
    override val builtFeatureCount: Int
        get() = _providers.count { !it.persistent && it.featureName in _loadedProviders }

    override fun init(context: Context, config: SdkConfig) {
        rwLock.writeLock().lock()
        try {
            check(!_initialized) { "MultiModuleSdkM already initialized. Call shutdown() first." }

            val appCtx = context.applicationContext
            val tracker = CreationTracker()
            _tracker = tracker

            // Foundation module — only caller-injected infrastructure and the tracker.
            // `SdkLogger` is contributed by ObservabilityKoinProvider (discovered
            // via ServiceLoader), loaded on demand the first time someone requests
            // `get<SdkLogger>()`.
            val foundation = module {
                single<Context> { appCtx }
                single<SdkConfig> { config }
                single<StorageBackend> { config.storageBackend }
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
        } finally {
            rwLock.writeLock().unlock()
        }
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        rwLock.readLock().lock()
        try {
            check(_initialized) { "MultiModuleSdkM not initialized." }

            // Ensure the provider for this service is loaded (cascade).
            ensureLoaded(clazz)

            val koin = _koinApp?.koin ?: error("MultiModuleSdkM not initialized.")
            val instance = koin.get<Any>(clazz.kotlin)
            return checkNotNull(clazz.cast(instance)) { "Cast failed for ${clazz.simpleName}" }
        } finally {
            rwLock.readLock().unlock()
        }
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
            val koin = _koinApp?.koin ?: error("koinApp is null")
            koin.loadModules(listOf(provider.module()))
            _loadedProviders.add(provider.featureName)
        }
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    override fun shutdown() {
        rwLock.writeLock().lock()
        try {
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
        } finally {
            rwLock.writeLock().unlock()
        }
    }
}

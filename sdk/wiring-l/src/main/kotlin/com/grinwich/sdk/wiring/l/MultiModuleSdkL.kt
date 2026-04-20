package com.grinwich.sdk.wiring.l

import android.content.Context
import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.koin.CreationTracker
import com.grinwich.sdk.contracts.koin.KoinFeatureProvider
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.ServiceLoader
import java.util.concurrent.locks.ReentrantReadWriteLock

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

    /**
     * Serialises lifecycle transitions (init/shutdown) with `writeLock` and lets
     * concurrent `get()` calls proceed in parallel with `readLock`. Guarantees
     * no resolver reads the Koin container after it has been closed — without
     * serialising every `get()` call.
     */
    private val rwLock = ReentrantReadWriteLock()

    override val isInitialized: Boolean get() = _initialized

    override val builtFeatureCount: Int get() = _tracker?.count ?: 0

    override fun init(context: Context, config: SdkConfig) {
        rwLock.writeLock().lock()
        try {
            check(!_initialized) { "MultiModuleSdkL already initialized. Call shutdown() first." }

            val appCtx = context.applicationContext
            val tracker = CreationTracker()
            _tracker = tracker

            // Foundation module — only caller-injected infrastructure and the tracker.
            // `SdkLogger` is NO LONGER declared here: it is contributed by
            // ObservabilityKoinProvider discovered via ServiceLoader — so L does
            // not require `implementation(:observability-impl)`.
            val foundation = module {
                single<Context> { appCtx }
                single<SdkConfig> { config }
                single<StorageBackend> { config.storageBackend }
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
        } finally {
            rwLock.writeLock().unlock()
        }
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        rwLock.readLock().lock()
        try {
            check(_initialized) { "MultiModuleSdkL not initialized." }
            val koin = _koinApp?.koin ?: error("MultiModuleSdkL not initialized.")
            val instance = koin.get<Any>(clazz.kotlin)
            return checkNotNull(clazz.cast(instance)) { "Cast failed for ${clazz.simpleName}" }
        } finally {
            rwLock.readLock().unlock()
        }
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    override fun shutdown() {
        rwLock.writeLock().lock()
        try {
            if (!_initialized) return
            _koinApp?.close()
            _koinApp = null
            _tracker?.clear()
            _tracker = null
            _initialized = false
        } finally {
            rwLock.writeLock().unlock()
        }
    }
}

package com.grinwich.sdk.wiring.h

import com.grinwich.sdk.api.MultiModuleSdkApi
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver
import com.grinwich.sdk.contracts.SyntheticFeatureProvider
import com.grinwich.sdk.contracts.error.DependencyResolutionException
import java.util.ServiceLoader

/**
 * Pattern H: Auto-Discovery with ServiceLoader.
 *
 * ALL features (including observability/logger) discovered via ServiceLoader.
 * Zero hardcoded defaults, zero impl imports.
 *
 * Logger is resolved lazily from ObservabilityProvider on first access.
 * Persists across init/shutdown cycles (resolver caches it).
 */
object MultiModuleSdkH : MultiModuleSdkApi {

    /** Serializes [init] and [shutdown]; [get] runs lock-free. */
    private val lifecycleLock = Any()
    private val resolver = Resolver()
    private var _initialized = false

    override val isInitialized: Boolean get() = _initialized

    override val builtFeatureCount: Int get() = resolver.builtFeatureCount

    override fun init(context: android.content.Context, config: SdkConfig) {
        synchronized(lifecycleLock) {
            check(!_initialized) { "MultiModuleSdkH already initialized. Call shutdown() first." }
            resolver.register(SyntheticFeatureProvider(mapOf(
                SdkConfig::class.java to config,
                android.content.Context::class.java to context.applicationContext,
            )))

            // H filters by DAGGER: only consumes providers with the Dagger flavor.
            ServiceLoader.load(FeatureProvider::class.java)
                .filter { it.flavor == Flavor.DAGGER }
                .forEach { resolver.register(it) }

            _initialized = true
        }
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkH not initialized." }
        return try {
            resolver.get(clazz)
        } catch (e: DependencyResolutionException) {
            // Concurrent shutdown can drain the resolver between the
            // initialization check above and the resolution below. Surface
            // that race as a lifecycle error so callers only see two
            // contracts: either the SDK returns a service, or it reports
            // "not initialized".
            if (!_initialized) throw IllegalStateException("MultiModuleSdkH not initialized.", e)
            throw e
        }
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    override fun shutdown() {
        synchronized(lifecycleLock) {
            if (!_initialized) return
            resolver.clear()
            _initialized = false
        }
    }
}

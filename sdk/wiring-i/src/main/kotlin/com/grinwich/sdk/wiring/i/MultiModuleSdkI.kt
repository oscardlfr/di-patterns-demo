package com.grinwich.sdk.wiring.i

import com.grinwich.sdk.api.MultiModuleSdkApi
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver
import com.grinwich.sdk.contracts.SyntheticFeatureProvider
import com.grinwich.sdk.contracts.error.DependencyResolutionException
import java.util.ServiceLoader

/**
 * Pattern I: Pure Resolver — zero DI framework.
 *
 * Same architecture as Pattern H (ServiceLoader + FeatureProvider + Resolver)
 * but features are built WITHOUT Dagger or any DI framework.
 * Each provider filtered by flavor=PURE builds services via constructors.
 *
 * Discovered via ServiceLoader.load(FeatureProvider::class.java) filtering
 * by flavor=PURE — separate from the Dagger providers (flavor=DAGGER) consumed by H.
 *
 * Zero KSP. Zero codegen. Zero DI framework dependency.
 */
object MultiModuleSdkI : MultiModuleSdkApi {

    /** Serializes [init] and [shutdown]; [get] runs lock-free. */
    private val lifecycleLock = Any()
    private val resolver = Resolver()
    private var _initialized = false

    override val isInitialized: Boolean get() = _initialized

    override val builtFeatureCount: Int get() = resolver.builtFeatureCount

    override fun init(context: android.content.Context, config: SdkConfig) {
        synchronized(lifecycleLock) {
            check(!_initialized) { "MultiModuleSdkI already initialized. Call shutdown() first." }
            resolver.register(SyntheticFeatureProvider(mapOf(
                SdkConfig::class.java to config,
                android.content.Context::class.java to context.applicationContext,
            )))

            // I filters by PURE: only consumes pure providers (no DI framework).
            ServiceLoader.load(FeatureProvider::class.java)
                .filter { it.flavor == Flavor.PURE }
                .forEach { resolver.register(it) }

            _initialized = true
        }
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkI not initialized." }
        return try {
            resolver.get(clazz)
        } catch (e: DependencyResolutionException) {
            // Concurrent shutdown can drain the resolver between the
            // initialization check above and the resolution below. Surface
            // that race as a lifecycle error so callers only see two
            // contracts: either the SDK returns a service, or it reports
            // "not initialized".
            if (!_initialized) throw IllegalStateException("MultiModuleSdkI not initialized.", e)
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

package com.grinwich.sdk.wiring.i

import com.grinwich.sdk.api.MultiModuleSdkApi
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver
import com.grinwich.sdk.contracts.SyntheticFeatureProvider
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

    private val resolver = Resolver()
    private var _initialized = false

    override val isInitialized: Boolean get() = _initialized

    override val builtFeatureCount: Int get() = resolver.builtFeatureCount

    override fun init(context: android.content.Context, config: SdkConfig) {
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

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkI not initialized." }
        return resolver.get(clazz)
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    override fun shutdown() {
        if (!_initialized) return
        resolver.clear()
        _initialized = false
    }
}

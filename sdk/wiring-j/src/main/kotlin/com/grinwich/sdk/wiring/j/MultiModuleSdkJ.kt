package com.grinwich.sdk.wiring.j

import com.grinwich.sdk.api.MultiModuleSdkApi
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver
import com.grinwich.sdk.contracts.SyntheticFeatureProvider
import java.util.ServiceLoader

/**
 * Pattern J: kotlin-inject — modern compile-time DI.
 *
 * Same architecture as Pattern H (ServiceLoader + FeatureProvider + Resolver)
 * but features use kotlin-inject Components instead of Dagger Components.
 *
 * Discovered via ServiceLoader.load(FeatureProvider::class.java) filtering
 * by flavor=KI — separate from the Dagger providers (flavor=DAGGER) consumed by H.
 *
 * KSP generates Kotlin (not Java). Less boilerplate than Dagger.
 * Component = Module (no separate @Module class needed).
 */
object MultiModuleSdkJ : MultiModuleSdkApi {

    private val resolver = Resolver()
    private var _initialized = false

    override val isInitialized: Boolean get() = _initialized

    override val builtFeatureCount: Int get() = resolver.builtFeatureCount

    override fun init(context: android.content.Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkJ already initialized. Call shutdown() first." }
        resolver.register(SyntheticFeatureProvider(mapOf(
            SdkConfig::class.java to config,
            android.content.Context::class.java to context.applicationContext,
        )))

        // J filters by KI: only consumes providers that use kotlin-inject.
        ServiceLoader.load(FeatureProvider::class.java)
            .filter { it.flavor == Flavor.KI }
            .forEach { resolver.register(it) }

        _initialized = true
    }

    override fun <T : Any> get(clazz: Class<T>): T {
        check(_initialized) { "MultiModuleSdkJ not initialized." }
        return resolver.get(clazz)
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)

    override fun shutdown() {
        if (!_initialized) return
        resolver.clear()
        _initialized = false
    }
}

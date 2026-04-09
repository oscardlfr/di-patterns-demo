package com.grinwich.sdk.wiring.j

import com.grinwich.sdk.api.MultiModuleSdkApi
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.contracts.KIFeatureProvider
import com.grinwich.sdk.contracts.Resolver
import java.util.ServiceLoader

/**
 * Pattern J: kotlin-inject — modern compile-time DI.
 *
 * Same architecture as Pattern H (ServiceLoader + FeatureProvider + Resolver)
 * but features use kotlin-inject Components instead of Dagger Components.
 *
 * Discovered via ServiceLoader.load(KIFeatureProvider::class.java) —
 * separate from Dagger-based providers used by Pattern H.
 *
 * KSP generates Kotlin (not Java). Less boilerplate than Dagger.
 * Component = Module (no separate @Module class needed).
 */
object MultiModuleSdkJ : MultiModuleSdkApi {

    private val resolver = Resolver()
    private var _initialized = false

    override val isInitialized: Boolean get() = _initialized

    /** Number of provisions currently built. Useful for verifying lazy behavior in tests. */
    override val builtProvisionCount: Int get() = resolver.builtProvisionCount

    override fun init(context: android.content.Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkJ already initialized. Call shutdown() first." }
        resolver.init(context, config)

        ServiceLoader.load(KIFeatureProvider::class.java).forEach { provider ->
            resolver.register(provider)
        }

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

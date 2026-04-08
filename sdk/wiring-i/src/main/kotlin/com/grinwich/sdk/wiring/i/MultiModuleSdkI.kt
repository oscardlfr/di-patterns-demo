package com.grinwich.sdk.wiring.i

import com.grinwich.sdk.api.MultiModuleSdkApi
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.contracts.PureFeatureProvider
import com.grinwich.sdk.contracts.Resolver
import java.util.ServiceLoader

/**
 * Pattern I: Pure Resolver — zero DI framework.
 *
 * Same architecture as Pattern H (ServiceLoader + FeatureProvider + Resolver)
 * but features are built WITHOUT Dagger or any DI framework.
 * Each PureFeatureProvider directly constructs services via constructors.
 *
 * Discovered via ServiceLoader.load(PureFeatureProvider::class.java) —
 * separate from Dagger-based providers used by Pattern H.
 *
 * Zero KSP. Zero codegen. Zero DI framework dependency.
 */
object MultiModuleSdkI : MultiModuleSdkApi {

    private val resolver = Resolver()
    private var _initialized = false

    override val isInitialized: Boolean get() = _initialized

    /** Number of provisions currently built. Useful for verifying lazy behavior in tests. */
    override val builtProvisionCount: Int get() = resolver.builtProvisionCount

    override fun init(context: android.content.Context, config: SdkConfig) {
        check(!_initialized) { "MultiModuleSdkI already initialized. Call shutdown() first." }
        resolver.init(config)

        ServiceLoader.load(PureFeatureProvider::class.java).forEach { provider ->
            resolver.register(provider)
        }

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

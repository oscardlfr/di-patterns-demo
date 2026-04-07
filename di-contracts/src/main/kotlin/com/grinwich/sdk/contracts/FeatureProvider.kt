package com.grinwich.sdk.contracts

import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.api.SdkLogger

/**
 * Pattern H: self-registering feature provider.
 *
 * Each feature-impl declares a FeatureProvider that knows:
 * - What provision interface it builds (provisionClass)
 * - What services it exposes (services map)
 * - How to build itself (build function, deps resolved via Resolver)
 *
 * Dependencies are IMPLICIT — whatever you call resolver.provision() for
 * inside build() gets built first (DFS). No explicit dependency set.
 *
 * Discovered at runtime via ServiceLoader — requires no-arg constructor.
 */
abstract class FeatureProvider<P : Any>(val provisionClass: Class<P>) {

    /** Maps service type -> extractor from the built provision. */
    abstract val services: Map<Class<*>, (P) -> Any>

    /** Builds the provision. Use resolver for deps, config, and logger. */
    abstract fun build(resolver: Resolver): P

    internal fun buildUntyped(resolver: Resolver): Any = build(resolver)

    internal fun extractService(provision: Any, serviceClass: Class<*>): Any {
        val typed = checkNotNull(provisionClass.cast(provision)) {
            "Cannot cast ${provision::class.simpleName} to ${provisionClass.simpleName}"
        }
        return services[serviceClass]?.invoke(typed)
            ?: error("${provisionClass.simpleName} does not provide ${serviceClass.simpleName}")
    }
}

/**
 * Runtime resolver — builds provisions on demand via DFS.
 *
 * Holds config + logger so providers don't need constructor args
 * (required for ServiceLoader no-arg instantiation).
 */
class Resolver {

    private val providers = HashMap<Class<*>, FeatureProvider<*>>()
    private val serviceIndex = HashMap<Class<*>, Class<*>>()
    private val provisions = HashMap<Class<*>, Any>()
    private val resolvedServices = HashMap<Class<*>, Any>()

    lateinit var config: SdkConfig; private set
    lateinit var logger: SdkLogger; private set

    fun init(config: SdkConfig, logger: SdkLogger) {
        this.config = config
        this.logger = logger
    }

    fun register(provider: FeatureProvider<*>) {
        providers[provider.provisionClass] = provider
        for (serviceClass in provider.services.keys) {
            serviceIndex[serviceClass] = provider.provisionClass
        }
    }

    /** Resolve a service by type. Auto-builds the provision chain on demand. */
    fun <T : Any> get(clazz: Class<T>): T {
        resolvedServices[clazz]?.let {
            return checkNotNull(clazz.cast(it)) { "Cast failed for ${clazz.simpleName}" }
        }
        val provisionClass = serviceIndex[clazz]
            ?: error("No provider for ${clazz.simpleName}")
        ensureBuilt(provisionClass)
        val resolved = resolvedServices[clazz]
            ?: error("${clazz.simpleName} not available after building ${provisionClass.simpleName}")
        return checkNotNull(clazz.cast(resolved)) { "Cast failed for ${clazz.simpleName}" }
    }

    /** Get a built provision by type. Used by FeatureProvider.build() for dependencies. */
    fun <P : Any> provision(clazz: Class<P>): P {
        ensureBuilt(clazz)
        val built = provisions[clazz]
            ?: error("Provision ${clazz.simpleName} not available")
        return checkNotNull(clazz.cast(built)) { "Cast failed for ${clazz.simpleName}" }
    }

    private fun ensureBuilt(provisionClass: Class<*>) {
        if (provisions.containsKey(provisionClass)) return
        val provider = providers[provisionClass]
            ?: error("No provider registered for ${provisionClass.simpleName}")
        val provision = provider.buildUntyped(this)
        provisions[provisionClass] = provision
        for (serviceClass in provider.services.keys) {
            resolvedServices[serviceClass] = provider.extractService(provision, serviceClass)
        }
    }

    fun clear() {
        providers.clear()
        serviceIndex.clear()
        provisions.clear()
        resolvedServices.clear()
    }
}

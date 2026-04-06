package com.grinwich.sdk.contracts

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
 * Discovered at runtime via ServiceLoader — no central registration needed.
 */
abstract class FeatureProvider<P : Any>(val provisionClass: Class<P>) {

    /** Maps service type → extractor from the built provision. */
    abstract val services: Map<Class<*>, (P) -> Any>

    /** Builds the provision. Call resolver.provision() for dependencies — triggers DFS. */
    abstract fun build(resolver: Resolver, logger: SdkLogger): P

    /** Called by Resolver — type-safe bridge using Class.cast(). */
    internal fun buildUntyped(resolver: Resolver, logger: SdkLogger): Any = build(resolver, logger)

    /** Called by Resolver — extracts a service from the built provision. */
    internal fun extractService(provision: Any, serviceClass: Class<*>): Any {
        val typed = provisionClass.cast(provision)
        return services[serviceClass]?.invoke(typed)
            ?: error("${provisionClass.simpleName} does not provide ${serviceClass.simpleName}")
    }
}

/**
 * Runtime resolver — builds provisions on demand via DFS.
 *
 * Thread-safety: init is single-threaded, post-init is read-only (HashMap).
 */
class Resolver {

    private val providers = HashMap<Class<*>, FeatureProvider<*>>()
    private val serviceIndex = HashMap<Class<*>, Class<*>>()
    private val provisions = HashMap<Class<*>, Any>()
    private val resolvedServices = HashMap<Class<*>, Any>()

    fun register(provider: FeatureProvider<*>) {
        providers[provider.provisionClass] = provider
        for (serviceClass in provider.services.keys) {
            serviceIndex[serviceClass] = provider.provisionClass
        }
    }

    /** Resolve a service by type. Auto-builds the provision chain on demand. */
    fun <T : Any> get(clazz: Class<T>): T {
        resolvedServices[clazz]?.let { return clazz.cast(it) }

        val provisionClass = serviceIndex[clazz]
            ?: error("No provider for ${clazz.simpleName}")

        ensureBuilt(provisionClass)

        return clazz.cast(resolvedServices[clazz]
            ?: error("${clazz.simpleName} not available after building ${provisionClass.simpleName}"))
    }

    /** Get a built provision by type. Used by FeatureProvider.build() for dependencies. */
    fun <P : Any> provision(clazz: Class<P>): P {
        ensureBuilt(clazz)
        return clazz.cast(provisions[clazz]
            ?: error("Provision ${clazz.simpleName} not available"))
    }

    private fun ensureBuilt(provisionClass: Class<*>) {
        if (provisions.containsKey(provisionClass)) return

        val provider = providers[provisionClass]
            ?: error("No provider registered for ${provisionClass.simpleName}")

        // DFS: build() calls resolver.provision() which calls ensureBuilt() recursively
        val provision = provider.buildUntyped(this, logger!!)
        provisions[provisionClass] = provision

        // Extract and cache all services
        for (serviceClass in provider.services.keys) {
            resolvedServices[serviceClass] = provider.extractService(provision, serviceClass)
        }
    }

    private var logger: SdkLogger? = null

    fun init(logger: SdkLogger) {
        this.logger = logger
    }

    fun clear() {
        provisions.clear()
        resolvedServices.clear()
        logger = null
    }
}

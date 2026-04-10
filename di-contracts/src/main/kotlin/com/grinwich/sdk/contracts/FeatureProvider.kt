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
 * Dependencies are IMPLICIT — whatever you call resolver.get() for
 * inside build() gets built first (DFS). No explicit dependency set.
 *
 * Discovered at runtime via ServiceLoader — requires no-arg constructor.
 */
abstract class FeatureProvider<P : Any>(val provisionClass: Class<P>) {

    abstract val services: Map<Class<*>, (P) -> Any>

    /**
     * Si true, esta provision sobrevive a shutdown()/clear().
     * Provisions persistentes estan atadas al ciclo de vida de la app, no del SDK.
     *
     * Ejemplo: el logger tiene file handles, buffers, correlation IDs.
     * Destruirlo en cada shutdown pierde estado y es innecesario.
     *
     * Override en el provider concreto para activar:
     * ```
     * class ObservabilityProvider : FeatureProvider<ObservabilityProvisions>(...) {
     *     override val persistent = true
     * }
     * ```
     */
    open val persistent: Boolean = false

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
 * Logger is resolved lazily from the service map (ObservabilityProvider).
 * No hardcoded default — if no ObservabilityProvider is registered, accessing
 * logger will fail with a clear error.
 */
/**
 * Runtime resolver — builds provisions on demand via DFS.
 *
 * Thread-safe: ensureBuilt() is synchronized so concurrent get() calls
 * from multiple threads cannot cause double construction or partial state.
 * After first build, subsequent get() hits the cache without contention.
 */
class Resolver {

    private val lock = Any()
    private val providers = HashMap<Class<*>, FeatureProvider<*>>()
    private val serviceIndex = HashMap<Class<*>, Class<*>>()
    private val provisions = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()
    private val resolvedServices = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()

    lateinit var config: SdkConfig; private set

    /** Logger resolved lazily from ServiceLoader-discovered ObservabilityProvider. */
    val logger: SdkLogger get() = get(SdkLogger::class.java)

    fun init(config: SdkConfig) {
        this.config = config
    }

    fun init(context: android.content.Context, config: SdkConfig) {
        this.config = config
        val appCtx = context.applicationContext

        // ContextProvisions: persistente porque el applicationContext vive tanto como el proceso
        register(object : FeatureProvider<ContextProvisions>(ContextProvisions::class.java) {
            override val persistent = true
            override val services: Map<Class<*>, (ContextProvisions) -> Any> =
                mapOf(android.content.Context::class.java to { p: ContextProvisions -> p.appContext() })
            override fun build(resolver: Resolver) = object : ContextProvisions {
                override fun appContext() = appCtx
            }
        })
    }

    fun register(provider: FeatureProvider<*>) {
        providers[provider.provisionClass] = provider
        for (serviceClass in provider.services.keys) {
            serviceIndex[serviceClass] = provider.provisionClass
        }
        if (provider.persistent) {
            persistentClasses.add(provider.provisionClass)
        }
    }

    fun <T : Any> get(clazz: Class<T>): T {
        // Fast path: cache hit without lock (read-only after build)
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

    fun <P : Any> provision(clazz: Class<P>): P {
        ensureBuilt(clazz)
        val built = provisions[clazz]
            ?: error("Provision ${clazz.simpleName} not available")
        return checkNotNull(clazz.cast(built)) { "Cast failed for ${clazz.simpleName}" }
    }

    /**
     * Thread-safe DFS: synchronized on [lock] so only one thread builds at a time.
     * The check-then-build inside the lock prevents double construction.
     * Recursive calls from build() re-enter the same thread's lock (reentrant).
     */
    private fun ensureBuilt(provisionClass: Class<*>) {
        if (provisions.containsKey(provisionClass)) return // fast path without lock
        synchronized(lock) {
            if (provisions.containsKey(provisionClass)) return // double-check inside lock
            val provider = providers[provisionClass]
                ?: error("No provider registered for ${provisionClass.simpleName}")
            val provision = provider.buildUntyped(this)
            // Write services BEFORE marking provision as built.
            // Other threads use provisions.containsKey() as the gate (fast path outside lock).
            // If we wrote provisions first, a thread could see containsKey()=true
            // but resolvedServices[service] still null.
            for (serviceClass in provider.services.keys) {
                resolvedServices[serviceClass] = provider.extractService(provision, serviceClass)
            }
            provisions[provisionClass] = provision // LAST — gate for other threads
        }
    }

    /**
     * Number of non-persistent provisions currently built.
     *
     * Excludes persistent provisions (logger, context) because they survive
     * shutdown and are tied to the app lifecycle, not the SDK lifecycle.
     * This lets tests verify laziness of BUSINESS features without being
     * affected by infrastructure provisions that persist across cycles.
     */
    val builtProvisionCount: Int get() = provisions.keys.count { it !in persistentClasses }

    /**
     * Limpia el grafo de provisions construidas, preservando providers
     * y provisions persistentes (logger, context).
     *
     * Providers (el catalogo de FeatureProviders descubiertos via ServiceLoader)
     * se mantienen — no hay necesidad de re-escanear ServiceLoader en cada reinit.
     *
     * Provisions persistentes (marcadas en [persistentClasses]) sobreviven
     * el shutdown porque estan atadas al ciclo de vida de la app, no del SDK.
     * Ejemplo: el logger tiene file handles abiertos, correlation IDs, buffers.
     * Destruirlo y reconstruirlo en cada reinit perderia estado.
     */
    fun clear() {
        synchronized(lock) {
            // Preservar providers — ServiceLoader no necesita re-escanear
            // Preservar serviceIndex — es derivado de providers

            // Limpiar solo provisions de negocio, preservar persistentes
            val persistentProvisions = provisions.filterKeys { it in persistentClasses }
            val persistentServices = resolvedServices.filterKeys { key ->
                persistentClasses.any { cls ->
                    providers[cls]?.services?.containsKey(key) == true
                }
            }

            provisions.clear()
            resolvedServices.clear()

            provisions.putAll(persistentProvisions)
            resolvedServices.putAll(persistentServices)
        }
    }

    private val persistentClasses = mutableSetOf<Class<*>>()
}

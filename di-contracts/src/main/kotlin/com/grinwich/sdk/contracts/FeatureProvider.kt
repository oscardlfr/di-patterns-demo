package com.grinwich.sdk.contracts

import com.grinwich.sdk.contracts.error.CircularDependencyException
import com.grinwich.sdk.contracts.error.NoProviderFoundException
import com.grinwich.sdk.contracts.error.ProviderAlreadyFailedException
import com.grinwich.sdk.contracts.error.ProviderBuildException
import com.grinwich.sdk.contracts.error.ServiceCastException
import com.grinwich.sdk.contracts.error.ServiceNotAvailableException
import java.util.concurrent.ConcurrentHashMap

/**
 * Provider implementation flavor.
 *
 * - [DAGGER]: feature-impl builds with Dagger (@Component + KSP).
 * - [PURE]: feature-impl builds by invoking constructors manually.
 * - [KI]: feature-impl builds with kotlin-inject (@Component + KSP).
 * - [SYNTHETIC]: provider injected by the wiring (e.g. Context, SdkConfig).
 *   Not discovered via ServiceLoader — the wiring constructs and registers it.
 *
 * H filters by [DAGGER], I by [PURE], J by [KI]. Wirings ignore [SYNTHETIC] in
 * ServiceLoader filters (they are never there). Synthetics are always
 * registered manually.
 */
enum class Flavor { DAGGER, PURE, KI, SYNTHETIC }

/**
 * Feature contribution to the SDK, resolved by [Resolver] (imperative axis).
 *
 * The provider publishes a map of constructed services directly (not an
 * intermediate typed "provision"), and wirings filter by [flavor] when
 * discovering them via ServiceLoader.
 *
 * Discovery via `ServiceLoader.load(FeatureProvider::class.java)` — requires a
 * no-arg constructor on every concrete implementation. Synthetics do not
 * require a no-arg ctor — the wiring constructs them directly.
 */
abstract class FeatureProvider : FeatureContribution {

    /** Implementation flavor. */
    abstract val flavor: Flavor

    /**
     * Service interfaces this provider exposes.
     * Must match the keys of the map returned by [build].
     */
    abstract override val services: Set<Class<*>>

    /**
     * If `true`, this contribution survives `shutdown()`/`clear()`.
     * Persistent contributions are tied to the app lifecycle, not the SDK's.
     * Example: the logger holds file handles, buffers, correlation IDs.
     */
    override val persistent: Boolean = false

    /**
     * Builds the services of this feature. May request dependencies from the
     * [resolver] via `resolver.get(ServiceClass::class.java)`.
     *
     * The returned map must contain exactly the keys declared in [services].
     */
    abstract fun build(resolver: Resolver): Map<Class<*>, Any>
}

/**
 * "Synthetic" provider injected by the wiring during init() — wraps
 * dependencies supplied by the SDK caller (`Context`, `SdkConfig`) and
 * publishes them as regular services.
 *
 * Not discovered via ServiceLoader. Typical usage in the wiring:
 *
 * ```kotlin
 * resolver.register(SyntheticFeatureProvider(mapOf(
 *     SdkConfig::class.java to config,
 *     Context::class.java to context.applicationContext,
 * )))
 * ```
 *
 * Replaces the former `Resolver.bootstrap()` method, unifying the mechanism:
 * **every service comes from a `FeatureProvider`**, with no special paths in
 * the Resolver.
 */
class SyntheticFeatureProvider(
    private val provided: Map<Class<*>, Any>,
    override val persistent: Boolean = false,
) : FeatureProvider() {
    override val flavor: Flavor = Flavor.SYNTHETIC
    override val services: Set<Class<*>> = provided.keys
    override fun build(resolver: Resolver): Map<Class<*>, Any> = provided
}

/**
 * Runtime resolver — builds contributions on demand via implicit DFS.
 *
 * **100% neutral**: does not know any type from `:sdk:api`. Every service is
 * published as a `FeatureProvider` (including infra — Context, SdkConfig —
 * via [SyntheticFeatureProvider]). Single mechanism, single path.
 *
 * Thread-safe: `ensureBuilt()` uses a reentrant lock; concurrent access from
 * multiple threads never causes double construction or partial state. After
 * the first build, `get()` takes a fast path without contention.
 */
class Resolver {

    private val lock = Any()
    private val serviceIndex = HashMap<Class<*>, FeatureProvider>()
    private val built = java.util.Collections.newSetFromMap(ConcurrentHashMap<FeatureProvider, Boolean>())
    private val resolvedServices = ConcurrentHashMap<Class<*>, Any>()

    /**
     * Providers whose `build()` is in progress on some thread. Populated when
     * entering [ensureBuilt] under [lock] and drained in the `finally` block.
     * Membership in this set signals a cycle: the provider was reached again
     * before it finished building, so resolving any of its services would
     * recurse back into itself.
     */
    private val buildingProviders = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<FeatureProvider, Boolean>()
    )

    /**
     * Providers whose last `build()` attempt threw. A registry never retries
     * a failed provider: [clear] must run first, which also re-registers the
     * wiring-supplied synthetics with fresh state.
     */
    private val failedProviders = java.util.Collections.newSetFromMap(
        ConcurrentHashMap<FeatureProvider, Boolean>()
    )

    /**
     * Persistent providers keyed by class — O(1) dedup in [register].
     * `ServiceLoader.load()` returns a fresh instance on every init, but the
     * provider class is stable, so using the class as the key keeps the map
     * bounded to the number of distinct persistent contributions (~1-3).
     */
    private val persistentByClass = ConcurrentHashMap<kotlin.reflect.KClass<out FeatureProvider>, FeatureProvider>()

    /**
     * O(1) read of [builtFeatureCount]. Maintained by [ensureBuilt]/[clear]
     * instead of `built.count { ... }`, which was O(B×P).
     */
    private val nonPersistentBuiltCount = java.util.concurrent.atomic.AtomicInteger(0)

    fun register(provider: FeatureProvider) {
        // Persistent providers are tied to the process, not to the SDK lifecycle.
        // ServiceLoader.load() creates a fresh instance on every init, so a naive
        // `add` would make the persistent set grow without bound across
        // init/shutdown cycles. First-wins dedup keyed by class: if a persistent
        // provider of the same class is already registered, keep it and discard
        // the newcomer — the resolved service (e.g. the logger singleton) is
        // unchanged anyway.
        if (provider.persistent) {
            val existing = persistentByClass.putIfAbsent(provider::class, provider)
            if (existing != null) {
                for (svc in existing.services) serviceIndex[svc] = existing
                return
            }
        }
        for (svc in provider.services) {
            serviceIndex[svc] = provider
        }
    }

    fun <T : Any> get(clazz: Class<T>): T {
        resolvedServices[clazz]?.let { return castOrThrow(clazz, it) }
        val provider = serviceIndex[clazz] ?: throw NoProviderFoundException(clazz.simpleName)
        ensureBuilt(provider)
        val resolved = resolvedServices[clazz]
            ?: throw ServiceNotAvailableException(
                serviceName = clazz.simpleName,
                providerName = provider::class.java.simpleName,
            )
        return castOrThrow(clazz, resolved)
    }

    private fun <T : Any> castOrThrow(clazz: Class<T>, instance: Any): T = try {
        // `Class.cast` arrives as a Java platform type; assigning it to a
        // non-nullable T variable lets the compiler coerce without `!!`.
        val cast: T = clazz.cast(instance)
        cast
    } catch (e: ClassCastException) {
        throw ServiceCastException(clazz.simpleName, e)
    }

    private fun ensureBuilt(provider: FeatureProvider) {
        if (provider in built) return
        // Fast-fail for already-failed providers avoids a lock round-trip on
        // repeated resolutions. `failedProviders` only grows outside [clear],
        // so a stale read is harmless — the in-lock check catches it either
        // way.
        if (provider in failedProviders) {
            throw ProviderAlreadyFailedException(provider::class.java.simpleName)
        }
        synchronized(lock) {
            if (provider in built) return
            if (provider in failedProviders) {
                throw ProviderAlreadyFailedException(provider::class.java.simpleName)
            }
            // A second thread blocks at [synchronized] and cannot observe this
            // set mid-build; the only way to reach a provider that is already
            // here is a reentrant call on the same thread that put it there,
            // i.e. a cycle.
            if (provider in buildingProviders) {
                throw CircularDependencyException(provider::class.java.simpleName)
            }
            buildingProviders.add(provider)
            try {
                val map = provider.build(this)
                for ((svc, inst) in map) {
                    resolvedServices[svc] = inst
                }
                built.add(provider) // LAST — gate for other threads
                if (!provider.persistent) nonPersistentBuiltCount.incrementAndGet()
            } catch (e: com.grinwich.sdk.contracts.error.DependencyResolutionException) {
                // Propagate domain failures untouched: wrapping them would
                // obscure the original cause (cycles, missing providers in
                // upstream dependencies, ...).
                throw e
            } catch (t: Throwable) {
                failedProviders.add(provider)
                throw ProviderBuildException(provider::class.java.simpleName, t)
            } finally {
                buildingProviders.remove(provider)
            }
        }
    }

    /**
     * Number of non-persistent contributions built. **O(1)**.
     *
     * Excludes persistent ones (logger) because they survive shutdown and are
     * tied to the app lifecycle, not the SDK's.
     */
    val builtFeatureCount: Int get() = nonPersistentBuiltCount.get()

    /**
     * Clears the built graph, preserving only persistent providers.
     *
     * Synthetics (Context, SdkConfig) are NOT persistent by default — the
     * wiring re-registers them on the next init() with fresh values.
     * Observability (logger) IS persistent and survives.
     */
    fun clear() {
        synchronized(lock) {
            val persistent = persistentByClass.values
            // Services to preserve: those from persistent providers already built.
            val toKeep = persistent
                .filter { it in built }
                .flatMap { p -> p.services.mapNotNull { svc ->
                    resolvedServices[svc]?.let { svc to it }
                } }
                .toMap()

            // Reset index — the wiring re-registers fresh synthetics on next init.
            serviceIndex.clear()
            for (p in persistent) {
                if (p in built) {
                    for (svc in p.services) serviceIndex[svc] = p
                }
            }

            // Convert to HashSet for O(1) contains in retainAll (the Map's
            // `values` view uses O(N) linear scan for contains).
            built.retainAll(persistent.toHashSet())
            resolvedServices.clear()
            resolvedServices.putAll(toKeep)
            // Reset transient build state. `buildingProviders` only holds
            // entries while a thread is inside `ensureBuilt`; if `clear()` is
            // called concurrently with a build, the losing thread will drain
            // its own entry in `finally`. `failedProviders` is reset so the
            // next init starts from a clean slate.
            failedProviders.clear()
            nonPersistentBuiltCount.set(0)
        }
    }
}

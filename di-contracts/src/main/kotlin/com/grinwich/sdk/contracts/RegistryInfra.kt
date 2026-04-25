package com.grinwich.sdk.contracts

import com.grinwich.sdk.contracts.error.CircularDependencyException
import com.grinwich.sdk.contracts.error.DependencyResolutionException
import com.grinwich.sdk.contracts.error.NoProviderFoundException
import com.grinwich.sdk.contracts.error.ProviderAlreadyFailedException
import com.grinwich.sdk.contracts.error.ProviderBuildException
import com.grinwich.sdk.contracts.error.ServiceCastException
import com.grinwich.sdk.contracts.error.ServiceNotAvailableException

/**
 * Registry infrastructure for multi-module patterns that do NOT use
 * [ServiceLoader]. Replaces the former `ProvisionRegistry` + `ProvisionEntry`.
 *
 * Keyed by FEATURE ID (neutral marker class, e.g. `EncFeatureId::class.java`),
 * not by a `Provisions` interface. Each feature-impl defines its own internal
 * marker. `di-contracts` stays fully neutral: it imports no type from
 * `com.grinwich.sdk.api.*` nor from any feature-api.
 *
 * Two variants:
 * - [ServiceRegistry] (pattern E): eager with topological sort at init
 * - [AutoServiceRegistry] (pattern E2): lazy build-on-demand via `get<T>()`
 */

// ============================================================
// Pattern E: ServiceEntry + ServiceRegistry (eager, topo-sort)
// ============================================================

/**
 * Declares what a feature builds and which services it exposes.
 *
 * Self-installing: the entry installs itself into the registry through
 * [installInto]. Identity comes from [featureId] (neutral marker class).
 *
 * @param featureId Marker class identifying the feature (e.g. `EncFeatureId::class.java`)
 * @param dependencies Feature ids that must be registered beforehand
 * @param build Factory returning a `service class -> instance` map
 */
class ServiceEntry(
    val featureId: Class<*>,
    val dependencies: Set<Class<*>> = emptySet(),
    private val build: (ServiceRegistry) -> Map<Class<*>, Any>,
) {
    internal fun installInto(registry: ServiceRegistry) {
        for (dep in dependencies) {
            if (!registry.hasFeature(dep)) {
                throw NoProviderFoundException(dep.simpleName)
            }
        }
        val services = try {
            build(registry)
        } catch (e: DependencyResolutionException) {
            throw e
        } catch (t: Throwable) {
            throw ProviderBuildException(featureId.simpleName, t)
        }
        registry.putFeature(featureId)
        registry.putServices(services)
    }
}

/**
 * Eager registry that builds every feature at registration time, resolving
 * dependencies via topological sort.
 */
class ServiceRegistry {

    private val featuresBuilt = HashSet<Class<*>>()
    private val services = HashMap<Class<*>, Any>()

    fun register(entry: ServiceEntry) {
        entry.installInto(this)
    }

    fun registerAll(entries: List<ServiceEntry>) {
        for (entry in topoSort(entries)) {
            entry.installInto(this)
        }
    }

    fun <T : Any> get(clazz: Class<T>): T {
        val raw = services[clazz] ?: throw NoProviderFoundException(clazz.simpleName)
        return try {
            val cast: T = clazz.cast(raw)
            cast
        } catch (e: ClassCastException) {
            throw ServiceCastException(clazz.simpleName, e)
        }
    }

    fun hasFeature(featureId: Class<*>): Boolean = featureId in featuresBuilt

    internal fun putFeature(featureId: Class<*>) {
        featuresBuilt.add(featureId)
    }

    internal fun putServices(map: Map<Class<*>, Any>) {
        services.putAll(map)
    }

    val builtFeatureCount: Int get() = featuresBuilt.size

    fun clear() {
        featuresBuilt.clear()
        services.clear()
    }

    companion object {
        internal fun topoSort(entries: List<ServiceEntry>): List<ServiceEntry> {
            val byId = entries.associateBy { it.featureId }
            val inDegree = entries.associate {
                it.featureId to it.dependencies.count { d -> d in byId }
            }.toMutableMap()
            val queue = ArrayDeque(entries.filter { inDegree[it.featureId] == 0 })
            val result = mutableListOf<ServiceEntry>()

            while (queue.isNotEmpty()) {
                val entry = queue.removeFirst()
                result.add(entry)
                for (other in entries) {
                    if (entry.featureId in other.dependencies) {
                        val newDeg = (inDegree[other.featureId] ?: 0) - 1
                        inDegree[other.featureId] = newDeg
                        if (newDeg == 0) queue.add(other)
                    }
                }
            }

            if (result.size != entries.size) {
                val missing = entries.map { it.featureId.simpleName } -
                    result.map { it.featureId.simpleName }.toSet()
                // Kahn's topo-sort leaves every node on a cycle with nonzero
                // in-degree; report the first survivor as the cycle witness.
                throw CircularDependencyException(missing.first())
            }
            return result
        }
    }
}

// ============================================================
// Pattern E2: AutoServiceEntry + AutoServiceRegistry (lazy, DFS)
// ============================================================

/**
 * Evolution of [ServiceEntry]: declares [serviceClasses] up front so they can
 * be indexed at install time. Entries are INSTALLED (cataloged) at init, but
 * BUILT only on the first `get<T>()` that requires them.
 *
 * @param persistent When `true`, the entry's built services survive [AutoServiceRegistry.clear]
 * and remain available across init/shutdown cycles (e.g. logger).
 */
class AutoServiceEntry(
    val featureId: Class<*>,
    val dependencies: Set<Class<*>> = emptySet(),
    val serviceClasses: Set<Class<*>>,
    val persistent: Boolean = false,
    private val build: (AutoServiceRegistry) -> Map<Class<*>, Any>,
) {
    internal fun buildAndRegister(registry: AutoServiceRegistry) {
        val services = build(registry)
        registry.putServices(services)
        registry.putFeature(featureId) // LAST — gate for other threads
    }
}

/**
 * Lazy registry — `get<T>()` triggers recursive on-demand construction.
 * After the first build, subsequent `get<T>()` calls are a single HashMap
 * read (~2-4 ns).
 */
class AutoServiceRegistry {

    private val catalog = HashMap<Class<*>, AutoServiceEntry>()
    private val serviceIndex = HashMap<Class<*>, Class<*>>()

    private val lock = Any()
    internal val featuresBuilt = java.util.concurrent.ConcurrentHashMap<Class<*>, Boolean>()
    internal val services = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()
    private val persistentFeatures = java.util.concurrent.ConcurrentHashMap<Class<*>, Boolean>()
    private val failedFeatures = java.util.concurrent.ConcurrentHashMap<Class<*>, Boolean>()

    /**
     * O(1) read of [builtFeatureCount]. Maintained by [putFeature]/[clear]
     * instead of `featuresBuilt.keys.count { ... }`, which was O(F).
     */
    private val nonPersistentBuiltCount = java.util.concurrent.atomic.AtomicInteger(0)

    fun install(entry: AutoServiceEntry) {
        // Idempotent: featureId is a stable Class<*>, so repeated installs from
        // successive `init()` cycles overwrite the same entries (no growth).
        // ensureBuilt short-circuits on `featuresBuilt.containsKey(id)`, so
        // persistent features are not rebuilt — the cached service in `services`
        // wins via the fast path in `get()`.
        catalog[entry.featureId] = entry
        for (svc in entry.serviceClasses) {
            serviceIndex[svc] = entry.featureId
        }
        if (entry.persistent) persistentFeatures[entry.featureId] = true
    }

    fun installAll(entries: List<AutoServiceEntry>) {
        for (entry in entries) install(entry)
    }

    fun <T : Any> get(clazz: Class<T>): T {
        services[clazz]?.let { return castOrThrow(clazz, it) }

        val featureId = serviceIndex[clazz] ?: throw NoProviderFoundException(clazz.simpleName)

        ensureBuilt(featureId)

        val resolved = services[clazz]
            ?: throw ServiceNotAvailableException(
                serviceName = clazz.simpleName,
                providerName = featureId.simpleName,
            )
        return castOrThrow(clazz, resolved)
    }

    private fun <T : Any> castOrThrow(clazz: Class<T>, instance: Any): T = try {
        val cast: T = clazz.cast(instance)
        cast
    } catch (e: ClassCastException) {
        throw ServiceCastException(clazz.simpleName, e)
    }

    fun isBuilt(featureId: Class<*>): Boolean = featuresBuilt.containsKey(featureId)

    /**
     * Count of NON-persistent features built. **O(1)**. Persistent entries
     * (e.g. logger) are excluded because they survive shutdown and are tied to
     * the app lifecycle.
     */
    val builtFeatureCount: Int get() = nonPersistentBuiltCount.get()

    /**
     * Clears the built graph, preserving services of persistent entries.
     *
     * On the next init, `installAll()` repopulates the catalog and service index
     * with fresh entries; persistent features that were already built remain
     * in `featuresBuilt` and their resolved services remain in `services`, so
     * `get<T>()` returns the same instance across init/shutdown cycles.
     */
    fun clear() {
        synchronized(lock) {
            val persistentBuilt = persistentFeatures.keys.filter { featuresBuilt.containsKey(it) }
            val servicesToKeep = persistentBuilt
                .flatMap { id ->
                    catalog[id]?.serviceClasses?.mapNotNull { svc ->
                        services[svc]?.let { svc to it }
                    } ?: emptyList()
                }
                .toMap()

            catalog.clear()
            serviceIndex.clear()
            featuresBuilt.clear()
            services.clear()
            failedFeatures.clear()
            // persistentFeatures survives — repopulated by install() on next init.

            for (id in persistentBuilt) featuresBuilt[id] = true
            services.putAll(servicesToKeep)
            nonPersistentBuiltCount.set(0)
        }
    }

    internal fun putFeature(featureId: Class<*>) {
        if (featuresBuilt.putIfAbsent(featureId, true) == null
            && !persistentFeatures.containsKey(featureId)
        ) {
            nonPersistentBuiltCount.incrementAndGet()
        }
    }

    internal fun putServices(map: Map<Class<*>, Any>) {
        services.putAll(map)
    }

    /**
     * Iterative post-order DFS. Explicit stack avoids a JVM
     * `StackOverflowError` on deep chains (500+); the accompanying `visiting`
     * set turns structural cycles into a deterministic
     * [CircularDependencyException] instead of an unbounded loop that would
     * balloon the stack and eventually OOM the deque.
     */
    private fun ensureBuilt(featureId: Class<*>) {
        if (featuresBuilt.containsKey(featureId)) return
        if (failedFeatures.containsKey(featureId)) {
            throw ProviderAlreadyFailedException(featureId.simpleName)
        }
        synchronized(lock) {
            if (featuresBuilt.containsKey(featureId)) return
            if (failedFeatures.containsKey(featureId)) {
                throw ProviderAlreadyFailedException(featureId.simpleName)
            }

            val stack = ArrayDeque<Pair<Class<*>, Boolean>>()
            // Tracks ids currently on the DFS path — a dep that points back
            // into this set closes a cycle. Entries leave when the node is
            // popped in its `processed = true` pass.
            val visiting = HashSet<Class<*>>()
            stack.addLast(featureId to false)

            while (stack.isNotEmpty()) {
                val (id, processed) = stack.removeLast()
                if (featuresBuilt.containsKey(id)) continue
                if (failedFeatures.containsKey(id)) {
                    throw ProviderAlreadyFailedException(id.simpleName)
                }

                val entry = catalog[id]
                    ?: throw NoProviderFoundException(id.simpleName)

                if (processed) {
                    try {
                        entry.buildAndRegister(this)
                    } catch (e: DependencyResolutionException) {
                        throw e
                    } catch (t: Throwable) {
                        failedFeatures[id] = true
                        throw ProviderBuildException(id.simpleName, t)
                    }
                    visiting.remove(id)
                } else {
                    if (!visiting.add(id)) {
                        throw CircularDependencyException(id.simpleName)
                    }
                    stack.addLast(id to true)
                    for (dep in entry.dependencies) {
                        if (featuresBuilt.containsKey(dep)) continue
                        if (failedFeatures.containsKey(dep)) {
                            throw ProviderAlreadyFailedException(dep.simpleName)
                        }
                        if (dep in visiting) {
                            throw CircularDependencyException(dep.simpleName)
                        }
                        stack.addLast(dep to false)
                    }
                }
            }
        }
    }
}

package com.grinwich.sdk.contracts

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
            check(registry.hasFeature(dep)) {
                "${dep.simpleName} must be registered before ${featureId.simpleName}"
            }
        }
        val services = build(registry)
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

    fun <T : Any> get(clazz: Class<T>): T =
        checkNotNull(clazz.cast(services[clazz])) { "Service ${clazz.simpleName} not available." }

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

            check(result.size == entries.size) {
                val missing = entries.map { it.featureId.simpleName } -
                    result.map { it.featureId.simpleName }.toSet()
                "Dependency cycle detected involving: $missing"
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
        services[clazz]?.let { return checkNotNull(clazz.cast(it)) { "Cast failed for ${clazz.simpleName}" } }

        val featureId = serviceIndex[clazz]
            ?: error("No entry provides ${clazz.simpleName}.")

        ensureBuilt(featureId)

        return checkNotNull(clazz.cast(services[clazz])) {
            "${clazz.simpleName} not available after building ${featureId.simpleName}"
        }
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
     * Iterative post-order DFS. Explicit stack to avoid StackOverflowError on
     * deep chains (500+).
     */
    private fun ensureBuilt(featureId: Class<*>) {
        if (featuresBuilt.containsKey(featureId)) return
        synchronized(lock) {
            if (featuresBuilt.containsKey(featureId)) return

            val stack = ArrayDeque<Pair<Class<*>, Boolean>>()
            stack.addLast(featureId to false)

            while (stack.isNotEmpty()) {
                val (id, processed) = stack.removeLast()
                if (featuresBuilt.containsKey(id)) continue

                val entry = catalog[id]
                    ?: error("Feature ${id.simpleName} not installed in catalog.")

                if (processed) {
                    entry.buildAndRegister(this)
                } else {
                    stack.addLast(id to true)
                    for (dep in entry.dependencies) {
                        if (!featuresBuilt.containsKey(dep)) {
                            stack.addLast(dep to false)
                        }
                    }
                }
            }
        }
    }
}

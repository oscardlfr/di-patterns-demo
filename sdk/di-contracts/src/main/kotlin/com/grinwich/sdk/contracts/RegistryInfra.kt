package com.grinwich.sdk.contracts

/**
 * Registry infrastructure for multi-module DI patterns using provision interfaces.
 *
 * Unlike the monolithic E/E2 patterns (in impl-dagger-e/e2) which key entries by
 * concrete @Component class, these versions key by PROVISION INTERFACE.
 * This allows feature-impl modules to remain decoupled — they never reference
 * each other's @Component classes, only provision interfaces from di-contracts.
 *
 * Two flavors:
 * - [ProvisionRegistry] (Pattern E): eager build with topological sort at init
 * - [AutoProvisionRegistry] (Pattern E2): lazy build-on-demand via get<T>()
 */

// ============================================================
// Pattern E: ProvisionEntry + ProvisionRegistry (eager, topo-sort)
// ============================================================

/**
 * Declares everything needed to integrate a feature's provision into the SDK.
 *
 * @param P The provision interface type (e.g., EncProvisions)
 * @param provisionClass Class token for registration/lookup
 * @param dependencies Provision classes that must be registered first
 * @param build Factory that creates the provision (receives registry to resolve parents)
 * @param services Explicit service bindings — compile-time checked, NO reflection
 */
class ProvisionEntry<P>(
    val provisionClass: Class<P>,
    val dependencies: Set<Class<*>> = emptySet(),
    val build: (ProvisionRegistry) -> P,
    val services: (P) -> Map<Class<*>, Any>,
)

/**
 * Registry that holds provisions and their eagerly-resolved service instances.
 *
 * Design: identical to monolithic ComponentRegistry but keyed by provision interface types.
 * - HashMap (not ConcurrentHashMap): init is single-threaded, post-init is read-only
 * - Services stored as instances, not lambdas: Dagger scoped components already cache
 * - Topological sort via [registerAll]: entries declared in any order, registry sorts
 */
class ProvisionRegistry {

    private val provisions = HashMap<Class<*>, Any>()
    private val services = HashMap<Class<*>, Any>()

    /** Register a single entry. Dependencies must already be registered. */
    fun <P> register(entry: ProvisionEntry<P>) {
        for (dep in entry.dependencies) {
            check(provisions.containsKey(dep)) {
                "${dep.simpleName} must be registered before ${entry.provisionClass.simpleName}"
            }
        }
        val provision = entry.build(this)
        provisions[entry.provisionClass] = provision as Any
        services.putAll(entry.services(provision))
    }

    /**
     * Register multiple entries with automatic topological sorting (Kahn's algorithm).
     * Order-independent — the registry resolves build order from dependencies.
     */
    fun registerAll(entries: List<ProvisionEntry<*>>) {
        val sorted = topoSort(entries)
        for (entry in sorted) {
            // Type-safe by construction: entry was registered as ProvisionEntry<P>
            @Suppress("UNCHECKED_CAST")
            register(entry as ProvisionEntry<Any>)
        }
    }

    /** Retrieve a previously-registered provision by its interface type. */
    fun <P> provision(clazz: Class<P>): P =
        clazz.cast(provisions[clazz])
            ?: error("Provision ${clazz.simpleName} not registered.")

    /** Resolve a service by its interface type. */
    fun <T : Any> get(clazz: Class<T>): T =
        clazz.cast(services[clazz])
            ?: error("Service ${clazz.simpleName} not available.")

    fun hasProvision(clazz: Class<*>): Boolean = provisions.containsKey(clazz)

    fun clear() {
        provisions.clear()
        services.clear()
    }

    companion object {
        /** Kahn's algorithm — topological sort by provision dependencies. O(V+E). */
        internal fun topoSort(entries: List<ProvisionEntry<*>>): List<ProvisionEntry<*>> {
            val byClass = entries.associateBy { it.provisionClass }
            val inDegree = entries.associate {
                it.provisionClass to it.dependencies.count { d -> d in byClass }
            }.toMutableMap()
            val queue = ArrayDeque(entries.filter { inDegree[it.provisionClass] == 0 })
            val result = mutableListOf<ProvisionEntry<*>>()

            while (queue.isNotEmpty()) {
                val entry = queue.removeFirst()
                result.add(entry)
                for (other in entries) {
                    if (entry.provisionClass in other.dependencies) {
                        val newDeg = (inDegree[other.provisionClass] ?: 0) - 1
                        inDegree[other.provisionClass] = newDeg
                        if (newDeg == 0) queue.add(other)
                    }
                }
            }

            check(result.size == entries.size) {
                val missing = entries.map { it.provisionClass.simpleName } -
                    result.map { it.provisionClass.simpleName }.toSet()
                "Dependency cycle detected involving: $missing"
            }
            return result
        }
    }
}

// ============================================================
// Pattern E2: AutoProvisionEntry + AutoProvisionRegistry (lazy, DFS)
// ============================================================

/**
 * Evolution from [ProvisionEntry]: declares [serviceClasses] upfront for indexing.
 * Entries are INSTALLED (cataloged) at init, BUILT on first demand via get<T>().
 */
class AutoProvisionEntry<P>(
    val provisionClass: Class<P>,
    val dependencies: Set<Class<*>> = emptySet(),
    val serviceClasses: Set<Class<*>>,
    val build: (AutoProvisionRegistry) -> P,
    val services: (P) -> Map<Class<*>, Any>,
)

/**
 * Auto-initializing registry — lazy build-on-demand.
 *
 * get<T>() triggers: find entry -> build dependencies recursively -> build entry -> cache.
 * After first build, subsequent get<T>() is a single HashMap lookup (~2-4 ns).
 */
class AutoProvisionRegistry {

    // Phase 1: Catalog (installed but not yet built)
    private val catalog = HashMap<Class<*>, AutoProvisionEntry<*>>()
    private val serviceIndex = HashMap<Class<*>, Class<*>>()

    // Phase 2: Built state (populated on demand)
    internal val provisions = HashMap<Class<*>, Any>()
    internal val services = HashMap<Class<*>, Any>()

    /** Install an entry into the catalog. Does NOT build it. */
    fun install(entry: AutoProvisionEntry<*>) {
        catalog[entry.provisionClass] = entry
        for (svc in entry.serviceClasses) {
            serviceIndex[svc] = entry.provisionClass
        }
    }

    /** Install multiple entries. Order doesn't matter — building is on-demand. */
    fun installAll(entries: List<AutoProvisionEntry<*>>) {
        for (entry in entries) install(entry)
    }

    /**
     * Resolve a service by type. Auto-builds the providing provision (and all
     * transitive dependencies) if not yet built.
     */
    fun <T : Any> get(clazz: Class<T>): T {
        services[clazz]?.let { return clazz.cast(it) }

        val provisionClass = serviceIndex[clazz]
            ?: error("No entry provides ${clazz.simpleName}.")

        ensureBuilt(provisionClass)

        return clazz.cast(services[clazz])
            ?: error("${clazz.simpleName} not available after building ${provisionClass.simpleName}")
    }

    /** Retrieve a built provision by its interface type. Used by entry build lambdas. */
    fun <P> provision(clazz: Class<P>): P =
        clazz.cast(provisions[clazz])
            ?: error("Provision ${clazz.simpleName} not built. Dependency not declared?")

    fun isBuilt(clazz: Class<*>): Boolean = provisions.containsKey(clazz)

    fun clear() {
        catalog.clear()
        serviceIndex.clear()
        provisions.clear()
        services.clear()
    }

    /**
     * Recursively ensure a provision and all its dependencies are built.
     * DFS replaces Kahn's topo-sort — same correctness, naturally lazy.
     */
    private fun ensureBuilt(provisionClass: Class<*>) {
        if (provisions.containsKey(provisionClass)) return

        val entry = catalog[provisionClass]
            ?: error("Provision ${provisionClass.simpleName} not installed in catalog.")

        for (dep in entry.dependencies) {
            ensureBuilt(dep)
        }

        // Type-safe by construction: entry was registered as AutoProvisionEntry<P>
        @Suppress("UNCHECKED_CAST")
        val typedEntry = entry as AutoProvisionEntry<Any>
        val provision = typedEntry.build(this)
        provisions[entry.provisionClass] = provision
        services.putAll(typedEntry.services(provision))
    }
}

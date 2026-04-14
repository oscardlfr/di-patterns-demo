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
 * Self-installing: the entry writes itself into the registry via [installInto],
 * where [P] is in scope. This avoids unchecked casts when the registry iterates
 * over `List<ProvisionEntry<*>>` and star-projection erases [P].
 *
 * @param P The provision interface type (e.g., EncProvisions)
 * @param provisionClass Class token for registration/lookup
 * @param dependencies Provision classes that must be registered first
 * @param build Factory that creates the provision (receives registry to resolve parents)
 * @param services Explicit service bindings — compile-time checked, NO reflection
 */
class ProvisionEntry<P : Any>(
    val provisionClass: Class<P>,
    val dependencies: Set<Class<*>> = emptySet(),
    private val build: (ProvisionRegistry) -> P,
    private val services: (P) -> Map<Class<*>, Any>,
) {
    internal fun installInto(registry: ProvisionRegistry) {
        for (dep in dependencies) {
            check(registry.hasProvision(dep)) {
                "${dep.simpleName} must be registered before ${provisionClass.simpleName}"
            }
        }
        val provision: P = build(registry)
        registry.putProvision(provisionClass, provision)
        registry.putServices(services(provision))
    }
}

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
    fun register(entry: ProvisionEntry<*>) {
        entry.installInto(this)
    }

    /**
     * Register multiple entries with automatic topological sorting (Kahn's algorithm).
     * Order-independent — the registry resolves build order from dependencies.
     */
    fun registerAll(entries: List<ProvisionEntry<*>>) {
        for (entry in topoSort(entries)) {
            entry.installInto(this)
        }
    }

    /** Retrieve a previously-registered provision by its interface type. */
    fun <P : Any> provision(clazz: Class<P>): P =
        checkNotNull(clazz.cast(provisions[clazz])) { "Provision ${clazz.simpleName} not registered." }

    /** Resolve a service by its interface type. */
    fun <T : Any> get(clazz: Class<T>): T =
        checkNotNull(clazz.cast(services[clazz])) { "Service ${clazz.simpleName} not available." }

    fun hasProvision(clazz: Class<*>): Boolean = provisions.containsKey(clazz)

    internal fun <P : Any> putProvision(clazz: Class<P>, provision: P) {
        provisions[clazz] = provision
    }

    internal fun putServices(map: Map<Class<*>, Any>) {
        services.putAll(map)
    }

    /** Number of provisions currently built. Useful for verifying lazy behavior in tests. */
    val builtProvisionCount: Int get() = provisions.size

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
 *
 * Self-installing: [buildAndRegister] performs the build+register sequence where
 * [P] is in scope, avoiding unchecked casts when the registry iterates over
 * `AutoProvisionEntry<*>` under star projection.
 */
class AutoProvisionEntry<P : Any>(
    val provisionClass: Class<P>,
    val dependencies: Set<Class<*>> = emptySet(),
    val serviceClasses: Set<Class<*>>,
    private val build: (AutoProvisionRegistry) -> P,
    private val services: (P) -> Map<Class<*>, Any>,
) {
    /**
     * Build this entry's provision and write it into [registry]. Writes services
     * first, then the provision itself — the provision map is the thread-safety
     * gate for concurrent readers (see [AutoProvisionRegistry]).
     */
    internal fun buildAndRegister(registry: AutoProvisionRegistry) {
        val provision: P = build(registry)
        registry.putServices(services(provision))
        registry.putProvision(provisionClass, provision) // LAST — gate for other threads
    }
}

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

    // Phase 2: Built state (populated on demand, thread-safe for concurrent reads)
    private val lock = Any()
    internal val provisions = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()
    internal val services = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()

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
        services[clazz]?.let { return checkNotNull(clazz.cast(it)) { "Cast failed for ${clazz.simpleName}" } }

        val provisionClass = serviceIndex[clazz]
            ?: error("No entry provides ${clazz.simpleName}.")

        ensureBuilt(provisionClass)

        return checkNotNull(clazz.cast(services[clazz])) {
            "${clazz.simpleName} not available after building ${provisionClass.simpleName}"
        }
    }

    /** Retrieve a built provision by its interface type. Used by entry build lambdas. */
    fun <P : Any> provision(clazz: Class<P>): P =
        checkNotNull(clazz.cast(provisions[clazz])) { "Provision ${clazz.simpleName} not built. Dependency not declared?" }

    fun isBuilt(clazz: Class<*>): Boolean = provisions.containsKey(clazz)

    /** Number of provisions currently built. Useful for verifying lazy behavior in tests. */
    val builtProvisionCount: Int get() = provisions.size

    fun clear() {
        synchronized(lock) {
            catalog.clear()
            serviceIndex.clear()
            provisions.clear()
            services.clear()
        }
    }

    internal fun <P : Any> putProvision(clazz: Class<P>, provision: P) {
        provisions[clazz] = provision
    }

    internal fun putServices(map: Map<Class<*>, Any>) {
        services.putAll(map)
    }

    /**
     * Iterative post-order DFS — ensure a provision and all its dependencies are built.
     * Uses an explicit stack to avoid StackOverflowError on deep chains (500+).
     *
     * Each stack entry is a (Class, processed) pair. On first visit, the node pushes
     * itself back as "processed" and then pushes its deps. When popped as "processed",
     * all deps are guaranteed to be built.
     */
    private fun ensureBuilt(provisionClass: Class<*>) {
        if (provisions.containsKey(provisionClass)) return // fast path without lock
        synchronized(lock) {
            if (provisions.containsKey(provisionClass)) return // double-check inside lock

            val stack = ArrayDeque<Pair<Class<*>, Boolean>>()
            stack.addLast(provisionClass to false)

            while (stack.isNotEmpty()) {
                val (cls, processed) = stack.removeLast()
                if (provisions.containsKey(cls)) continue

                val entry = catalog[cls]
                    ?: error("Provision ${cls.simpleName} not installed in catalog.")

                if (processed) {
                    entry.buildAndRegister(this)
                } else {
                    stack.addLast(cls to true)
                    for (dep in entry.dependencies) {
                        if (!provisions.containsKey(dep)) {
                            stack.addLast(dep to false)
                        }
                    }
                }
            }
        }
    }
}

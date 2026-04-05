package com.grinwich.sdk.registry

/**
 * Marker interface for all Dagger components managed by the registry.
 *
 * Unlike reflection-based approaches that discover services by scanning methods,
 * services are registered explicitly via [FeatureEntry.services] — compile-time safe.
 *
 * In a multi-module corporate setup, each Gradle module's Dagger @Component
 * extends this interface. The module exports a [FeatureEntry] that the SDK
 * facade collects at init time — no centralized enum needed.
 */
interface DiComponent

/**
 * Declares everything needed to integrate a feature into the SDK.
 *
 * Each Gradle module (e.g., :integration:features:storage) exports one of these.
 * The SDK facade collects all entries and registers them — topologically sorted
 * by [dependencies]. No changes to the facade when adding new features.
 *
 * @param C The Dagger component type this entry produces
 * @param componentClass Class token for component registration/lookup
 * @param dependencies Component classes that must be registered first
 * @param build Factory that creates the component (receives registry to resolve parents)
 * @param services Explicit service bindings — compile-time checked, NO reflection.
 *                 Returns instances directly (eager), not lambdas (avoids per-access overhead).
 */
class FeatureEntry<C : DiComponent>(
    val componentClass: Class<C>,
    val dependencies: Set<Class<out DiComponent>> = emptySet(),
    val build: (ComponentRegistry) -> C,
    val services: (C) -> Map<Class<*>, Any>,
)

/**
 * Registry that holds Dagger components and their eagerly-resolved service instances.
 *
 * Design choices for corporate SDK viability:
 * - [HashMap] not ConcurrentHashMap: init is single-threaded, post-init is read-only
 *   (effectively immutable after init → safe for concurrent reads without sync overhead)
 * - Services stored as instances, not lambdas: Dagger scoped components already cache
 *   singletons, so invoking component.service() during registration is safe and
 *   eliminates the lambda invoke() overhead on every get() call
 * - Topological sorting via [registerAll]: each module declares its deps, the registry
 *   sorts and registers in correct order — no manual ordering needed
 */
class ComponentRegistry {

    private val components = HashMap<Class<out DiComponent>, DiComponent>()
    private val services = HashMap<Class<*>, Any>()

    /** Register a single entry. Dependencies must already be registered. */
    fun <C : DiComponent> register(entry: FeatureEntry<C>) {
        for (dep in entry.dependencies) {
            check(components.containsKey(dep)) {
                "${dep.simpleName} must be registered before ${entry.componentClass.simpleName}"
            }
        }
        val component = entry.build(this)
        components[entry.componentClass] = component
        services.putAll(entry.services(component))
    }

    /**
     * Register multiple entries with automatic topological sorting.
     *
     * In a corporate multi-module setup, each module exports a [FeatureEntry].
     * The SDK facade collects them all and passes them here — order doesn't matter,
     * the registry resolves the correct build order from [FeatureEntry.dependencies].
     *
     * @param entries All feature entries to register (order-independent)
     * @throws IllegalStateException if a dependency cycle is detected
     */
    fun registerAll(entries: List<FeatureEntry<*>>) {
        val sorted = topoSort(entries)
        for (entry in sorted) {
            @Suppress("UNCHECKED_CAST")
            register(entry as FeatureEntry<DiComponent>)
        }
    }

    /** Retrieve a previously-registered component by its class. */
    @Suppress("UNCHECKED_CAST")
    fun <C : DiComponent> component(clazz: Class<C>): C =
        components[clazz] as? C
            ?: error("Component ${clazz.simpleName} not registered. Check feature ordering.")

    /** Resolve a service by its interface type. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(clazz: Class<T>): T =
        services[clazz] as? T
            ?: error("Service ${clazz.simpleName} not available. Did you init the right feature?")

    /** Check if a component is registered. */
    fun hasComponent(clazz: Class<out DiComponent>): Boolean = components.containsKey(clazz)

    fun clear() {
        components.clear()
        services.clear()
    }

    companion object {
        /**
         * Kahn's algorithm — topological sort by component dependencies.
         * O(V+E) where V = entries, E = dependency edges.
         */
        internal fun topoSort(entries: List<FeatureEntry<*>>): List<FeatureEntry<*>> {
            val byClass = entries.associateBy { it.componentClass }
            val inDegree = entries.associate { it.componentClass to it.dependencies.count { d -> d in byClass } }.toMutableMap()
            val queue = ArrayDeque(entries.filter { inDegree[it.componentClass] == 0 })
            val result = mutableListOf<FeatureEntry<*>>()

            while (queue.isNotEmpty()) {
                val entry = queue.removeFirst()
                result.add(entry)
                // Find entries that depend on this one and decrement their in-degree
                for (other in entries) {
                    if (entry.componentClass in other.dependencies) {
                        val newDeg = (inDegree[other.componentClass] ?: 0) - 1
                        inDegree[other.componentClass] = newDeg
                        if (newDeg == 0) queue.add(other)
                    }
                }
            }

            check(result.size == entries.size) {
                val missing = entries.map { it.componentClass.simpleName } - result.map { it.componentClass.simpleName }.toSet()
                "Dependency cycle detected involving: $missing"
            }
            return result
        }
    }
}

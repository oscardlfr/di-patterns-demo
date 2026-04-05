package com.grinwich.sdk.auto

/**
 * Marker interface for all Dagger components managed by the registry.
 */
interface DiComponent

/**
 * Declares everything needed to integrate a feature into the SDK.
 *
 * Evolution from E's FeatureEntry:
 * - [serviceClasses] declares which services this entry provides BEFORE building.
 *   This enables the registry to resolve `get<T>()` → entry → auto-build.
 * - Entries are INSTALLED (cataloged) at init, BUILT on first demand.
 *
 * In a corporate multi-module setup, each Gradle module exports one of these.
 * The facade collects them at init — adding a module is one line.
 *
 * @param C The Dagger component type this entry produces
 * @param componentClass Class token for component registration/lookup
 * @param dependencies Component classes that must be built first
 * @param serviceClasses Service types this entry provides (declared upfront for indexing)
 * @param build Factory that creates the component (receives registry to resolve parents)
 * @param services Extracts service instances from built component (eager, not lambdas)
 */
class AutoFeatureEntry<C : DiComponent>(
    val componentClass: Class<C>,
    val dependencies: Set<Class<out DiComponent>> = emptySet(),
    val serviceClasses: Set<Class<*>>,
    val build: (AutoRegistry) -> C,
    val services: (C) -> Map<Class<*>, Any>,
)

/**
 * Auto-initializing registry — the core evolution from Pattern E.
 *
 * E's ComponentRegistry: entries registered = entries built (eager).
 * AutoRegistry: entries INSTALLED (cheap catalog) then BUILT on demand.
 *
 * `get<T>()` triggers: find entry → build dependencies recursively → build entry → cache.
 * After first build, subsequent `get<T>()` is a single HashMap lookup (~2-4 ns).
 *
 * Design choices:
 * - [HashMap] not ConcurrentHashMap: init single-threaded, post-init read-only
 * - Two-phase: install (index only) → build (on demand via get)
 * - Recursive ensureBuilt replaces topo-sort — same correctness, simpler code,
 *   and only builds what's actually needed (vs E which builds everything upfront)
 * - Service instances cached eagerly per component (same as E) — Dagger scoped
 *   singletons make this safe
 */
class AutoRegistry {

    // Phase 1: Catalog (installed but not yet built)
    private val catalog = HashMap<Class<out DiComponent>, AutoFeatureEntry<*>>()
    private val serviceIndex = HashMap<Class<*>, Class<out DiComponent>>()

    // Phase 2: Built state (populated on demand)
    internal val components = HashMap<Class<out DiComponent>, DiComponent>()
    internal val services = HashMap<Class<*>, Any>()

    /** Install an entry into the catalog. Does NOT build it. O(1) per service class. */
    fun install(entry: AutoFeatureEntry<*>) {
        catalog[entry.componentClass] = entry
        for (svc in entry.serviceClasses) {
            serviceIndex[svc] = entry.componentClass
        }
    }

    /** Install multiple entries. Order doesn't matter — building is on-demand. */
    fun installAll(entries: List<AutoFeatureEntry<*>>) {
        for (entry in entries) install(entry)
    }

    /**
     * Resolve a service by type. Auto-builds the providing component (and all
     * transitive dependencies) if not yet built.
     *
     * First call for a service: O(depth) recursive builds.
     * Subsequent calls: single HashMap.get (~2-4 ns).
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(clazz: Class<T>): T {
        // Fast path: already built
        services[clazz]?.let { return it as T }

        // Find which component provides this service
        val componentClass = serviceIndex[clazz]
            ?: error("No entry provides ${clazz.simpleName}. Install a FeatureEntry first.")

        // Auto-build the component and all its dependencies
        ensureBuilt(componentClass)

        return services[clazz] as? T
            ?: error("${clazz.simpleName} not available after building ${componentClass.simpleName}")
    }

    /** Check if a component has been built (not just installed). */
    fun isBuilt(clazz: Class<out DiComponent>): Boolean = components.containsKey(clazz)

    /** Check if a service is available (its component was built). */
    fun hasService(clazz: Class<*>): Boolean = services.containsKey(clazz)

    /** Retrieve a built component by class. Used by entry build lambdas. */
    @Suppress("UNCHECKED_CAST")
    fun <C : DiComponent> component(clazz: Class<C>): C =
        components[clazz] as? C
            ?: error("Component ${clazz.simpleName} not built. Dependency not declared?")

    /** Clear built state only — catalog stays, components can be re-built. */
    fun clearBuilt() {
        components.clear()
        services.clear()
    }

    /** Clear everything — catalog + built state. */
    fun clear() {
        catalog.clear()
        serviceIndex.clear()
        components.clear()
        services.clear()
    }

    /**
     * Recursively ensure a component and all its dependencies are built.
     * Replaces E's Kahn's topo-sort — same correctness via DFS, simpler,
     * and naturally lazy (only builds what's needed).
     */
    private fun ensureBuilt(componentClass: Class<out DiComponent>) {
        if (components.containsKey(componentClass)) return

        val entry = catalog[componentClass]
            ?: error("Component ${componentClass.simpleName} not installed in catalog.")

        // Recursively build dependencies first
        for (dep in entry.dependencies) {
            ensureBuilt(dep)
        }

        // Build this component
        @Suppress("UNCHECKED_CAST")
        val typedEntry = entry as AutoFeatureEntry<DiComponent>
        val component = typedEntry.build(this)
        components[entry.componentClass] = component
        services.putAll(typedEntry.services(component))
    }
}

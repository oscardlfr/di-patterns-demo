package com.grinwich.sdk.contracts

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracker for lazy creation in compile-time DI patterns (O2/P2/Q2).
 *
 * Remains an `object` so feature-impl `@Provides` methods can call [mark]
 * as a static call. The active [Instance] is thread-local, so multiple
 * wirings coexist without clobbering each other's counters — a prerequisite
 * for tests that keep several O2/P2/Q2 SDKs initialised simultaneously
 * (e.g. `crossPatternIsolation`).
 *
 * Each wiring holds its own [Instance] and activates it around a `get<T>()`
 * via [Instance.withActive]. During that block, `@Provides` methods running
 * on the same thread see this instance and call [Instance.mark] on it.
 */
object LazyCreationTracker {

    private val _active = ThreadLocal<Instance?>()

    /** Called by feature-impl `@Provides` methods — delegates to the thread-local active instance. */
    fun mark(name: String) { _active.get()?.mark(name) }

    /**
     * Creates a fresh tracker instance. Kept for API compatibility with the
     * previous volatile-singleton design; the returned instance is NOT
     * automatically installed — callers use [Instance.withActive] to scope it.
     */
    fun activate(): Instance = Instance()

    /** No-op since the active instance is now thread-local and scoped by [Instance.withActive]. */
    fun deactivate() { /* intentional no-op */ }

    /** Per-init-cycle tracker. Wiring modules hold a reference for `builtFeatureCount`. */
    class Instance {
        private val _created: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val count: Int get() = _created.size
        fun mark(name: String) { _created.add(name) }
        fun clear() { _created.clear() }

        /**
         * Installs this instance as active on the current thread for the duration
         * of [block]; restores the previous value on exit so nested/foreign
         * wirings are preserved.
         */
        fun <R> withActive(block: () -> R): R {
            val prev = _active.get()
            _active.set(this)
            try {
                return block()
            } finally {
                _active.set(prev)
            }
        }
    }
}

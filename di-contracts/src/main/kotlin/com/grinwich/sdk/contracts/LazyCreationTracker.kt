package com.grinwich.sdk.contracts

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracker for lazy creation in compile-time DI patterns (O2/P2/Q2).
 *
 * Remains an `object` so that feature-impl @Provides methods can call
 * [mark] as a static call. Internally delegates to the [Instance] set
 * by [activate], so each SDK wiring module gets its own isolated counter
 * that is released on shutdown (no cross-test leakage).
 */
object LazyCreationTracker {

    @Volatile
    private var _active: Instance? = null

    /** Create and install a fresh tracker instance. Returns it for local storage. */
    fun activate(): Instance {
        val tracker = Instance()
        _active = tracker
        return tracker
    }

    /** Called by feature-impl @Provides methods — delegates to the active instance. */
    fun mark(name: String) { _active?.mark(name) }

    /** Detach the active instance so the object holds no references. */
    fun deactivate() { _active = null }

    /** Per-init-cycle tracker. Wiring modules hold a reference for [builtProvisionCount]. */
    class Instance {
        private val _created: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val count: Int get() = _created.size
        fun mark(name: String) { _created.add(name) }
        fun clear() { _created.clear() }
    }
}

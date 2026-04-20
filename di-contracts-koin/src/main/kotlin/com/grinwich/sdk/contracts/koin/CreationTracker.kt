package com.grinwich.sdk.contracts.koin

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks actual singleton creation inside Koin modules.
 *
 * Injected into the Koin graph by wiring modules. Each KoinFeatureProvider's
 * `single{}` definitions call `get<CreationTracker>().mark(featureName)`
 * so that [builtFeatureCount] reflects real instantiation, not just registration.
 */
class CreationTracker {
    private val _created: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Number of distinct feature groups that have created at least one singleton. */
    val count: Int get() = _created.size

    /** Mark a feature group as having created a singleton. Thread-safe. */
    fun mark(featureName: String) { _created.add(featureName) }

    /** Reset tracking. Called on shutdown. */
    fun clear() { _created.clear() }
}

package com.grinwich.sdk.contracts.koin

import dev.whyoleg.sweetspi.Service
import org.koin.core.module.Module

/**
 * Patterns L, M, N: feature provider that contributes a Koin Module.
 *
 * Unlike [com.grinwich.sdk.contracts.FeatureProvider] (which uses the custom
 * Resolver for DFS), KoinFeatureProvider delegates dependency resolution
 * entirely to Koin. Each feature-impl declares its Koin module, services,
 * and required deps.
 *
 * Discovery:
 * - L/M: java.util.ServiceLoader via hand-written META-INF/services (class providers)
 * - N: sweet-spi via @ServiceProvider annotation (object providers, KMP-compatible)
 *
 * @Service enables sweet-spi to recognize this as a discoverable service type.
 *
 * @param featureName Human-readable name for tracking/debugging
 */
@Service
abstract class KoinFeatureProvider(val featureName: String) {

    /** Returns a Koin Module with this feature's bindings. */
    abstract fun module(): Module

    /** Service interfaces this provider exposes (e.g., EncryptionApi::class.java). */
    abstract val services: Set<Class<*>>

    /**
     * Service interfaces this feature requires from other providers.
     * Used by Pattern M for cascade loading: when this provider's module
     * is loaded on demand, requiredServices triggers loading of
     * providers that expose those services first.
     *
     * Pattern L ignores this (all modules loaded eagerly).
     */
    open val requiredServices: Set<Class<*>> = emptySet()

    /**
     * If true, this provider survives shutdown() and persists across
     * init/shutdown cycles (e.g., logger, context).
     */
    open val persistent: Boolean = false
}

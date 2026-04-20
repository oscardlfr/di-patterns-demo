package com.grinwich.sdk.contracts.koin

import com.grinwich.sdk.contracts.FeatureContribution
import dev.whyoleg.sweetspi.Service
import org.koin.core.module.Module

/**
 * Patterns L, M, N, O, P, Q: provider that contributes a Koin `Module`.
 *
 * Unlike [com.grinwich.sdk.contracts.FeatureProvider] (which delegates
 * resolution to the imperative `Resolver`), KoinFeatureProvider delegates
 * resolution entirely to Koin. Each feature-impl declares its `Module`, the
 * services it exposes, and (optionally) the services it requires.
 *
 * Implements [FeatureContribution]: shares the `services` + `persistent`
 * header with the Resolver axis. What diverges (declarative `module()` DSL
 * vs imperative `build(resolver)`) stays outside the shared contract.
 *
 * Discovery:
 * - L/M: `java.util.ServiceLoader` with a hand-written META-INF/services
 * - N: sweet-spi via `@ServiceProvider` (objects, KMP-compatible)
 *
 * @Service lets sweet-spi recognize it as a discoverable type.
 *
 * @param featureName Human-readable name for tracking/debug
 */
@Service
abstract class KoinFeatureProvider(val featureName: String) : FeatureContribution {

    /** Returns a Koin `Module` with this feature's bindings. */
    abstract fun module(): Module

    /** Service interfaces this provider exposes (e.g. `EncryptionApi::class.java`). */
    abstract override val services: Set<Class<*>>

    /**
     * Service interfaces this feature REQUIRES from other providers.
     * Used by Pattern M (cascade loading): when this module is loaded on
     * demand, `requiredServices` first triggers loading of providers that
     * expose those services.
     *
     * Pattern L ignores this (all modules are loaded eagerly).
     */
    open val requiredServices: Set<Class<*>> = emptySet()

    /**
     * If `true`, this provider survives `shutdown()` and persists across
     * init/shutdown cycles (e.g. logger, applicationContext).
     */
    override val persistent: Boolean = false
}

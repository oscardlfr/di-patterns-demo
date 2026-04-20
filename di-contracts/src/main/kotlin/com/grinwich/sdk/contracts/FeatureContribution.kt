package com.grinwich.sdk.contracts

/**
 * Common header for feature contributions to the SDK.
 *
 * Implemented by:
 * - [FeatureProvider] (Resolver axis: patterns H, I, J, K)
 * - [com.grinwich.sdk.contracts.koin.KoinFeatureProvider] (Koin axis: patterns L, M, N, O, P, Q)
 *
 * Both axes share what the provider publishes (`services`) and its lifecycle
 * relative to the SDK (`persistent`), but diverge in how services are built.
 * The Resolver is imperative (each provider requests deps from a `Resolver`);
 * Koin is declarative (each provider returns a `Module`). Forcing unification
 * of the construction model would yield an ugly contract littered with
 * `error("unsupported")`, so only the header is shared.
 */
interface FeatureContribution {
    /** Service interfaces this provider exposes (e.g. `EncryptionApi::class.java`). */
    val services: Set<Class<*>>

    /**
     * If `true`, the contribution survives `shutdown()` and persists across
     * init/shutdown cycles (e.g. logger, applicationContext).
     */
    val persistent: Boolean
}

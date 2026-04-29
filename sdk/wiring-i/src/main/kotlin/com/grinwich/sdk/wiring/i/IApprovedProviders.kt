package com.grinwich.sdk.wiring.i

/**
 * Closed allowlist of FeatureProvider FQNs that Pattern I (Pure
 * Resolver — zero DI framework) will register during init.
 *
 * Same defence as `HApprovedProviders` (Pattern H) — the only
 * difference is the flavor: every entry here is a `Flavor.PURE`
 * provider authored by the SDK team. Adding a new feature module
 * requires a coordinated PR touching this set.
 */
internal object IApprovedProviders {
    val FQNS: Set<String> = setOf(
        "com.grinwich.sdk.contracts.SyntheticFeatureProvider",

        "com.grinwich.sdk.feature.observability.ObservabilityPureProvider",
        "com.grinwich.sdk.feature.core.CorePureProvider",
        "com.grinwich.sdk.feature.enc.EncPureProvider",
        "com.grinwich.sdk.feature.auth.AuthPureProvider",
        "com.grinwich.sdk.feature.stor.StorPureProvider",
        "com.grinwich.sdk.feature.ana.AnaPureProvider",
        "com.grinwich.sdk.feature.syn.SynPureProvider",
    )
}

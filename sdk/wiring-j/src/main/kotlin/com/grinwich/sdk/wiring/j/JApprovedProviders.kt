package com.grinwich.sdk.wiring.j

/**
 * Closed allowlist of FeatureProvider FQNs that Pattern J (kotlin-inject)
 * will register during init.
 *
 * Same defence as `HApprovedProviders` (Pattern H) — the only difference
 * is the flavor: every entry here is a `Flavor.KI` provider authored by
 * the SDK team. Adding a new feature module requires a coordinated PR
 * touching this set.
 */
internal object JApprovedProviders {
    val FQNS: Set<String> = setOf(
        "com.grinwich.sdk.contracts.SyntheticFeatureProvider",

        "com.grinwich.sdk.feature.observability.ObservabilityKIProvider",
        "com.grinwich.sdk.feature.core.CoreKIProvider",
        "com.grinwich.sdk.feature.enc.EncKIProvider",
        "com.grinwich.sdk.feature.auth.AuthKIProvider",
        "com.grinwich.sdk.feature.stor.StorKIProvider",
        "com.grinwich.sdk.feature.ana.AnaKIProvider",
        "com.grinwich.sdk.feature.syn.SynKIProvider",
    )
}

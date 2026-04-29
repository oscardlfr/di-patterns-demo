package com.grinwich.sdk.wiring.k

/**
 * Closed allowlist of FeatureProvider FQNs that Pattern K (AndroidManifest
 * meta-data discovery) will register during init.
 *
 * Same defence as `HApprovedProviders` (Pattern H). The vector is slightly
 * different — manifest merger picks up `<meta-data>` entries from any
 * dependency that declares them under [ComponentDiscoveryService] — but
 * the mitigation is identical: a malicious dependency cannot register a
 * provider whose FQN is not on this list.
 *
 * Pattern K reuses the same Dagger-flavor provider classes as Pattern H,
 * so this set mirrors `HApprovedProviders.FQNS`.
 */
internal object KApprovedProviders {
    val FQNS: Set<String> = setOf(
        "com.grinwich.sdk.contracts.SyntheticFeatureProvider",

        "com.grinwich.sdk.feature.observability.ObservabilityProvider",
        "com.grinwich.sdk.feature.core.CoreProvider",
        "com.grinwich.sdk.feature.enc.EncProvider",
        "com.grinwich.sdk.feature.auth.AuthProvider",
        "com.grinwich.sdk.feature.stor.StorProvider",
        "com.grinwich.sdk.feature.ana.AnaProvider",
        "com.grinwich.sdk.feature.syn.SynProvider",
    )
}

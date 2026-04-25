package com.grinwich.sdk.wiring.h

/**
 * Closed allowlist of FeatureProvider FQNs that Pattern H will register
 * during init.
 *
 * Every entry is a class authored, audited and shipped by the SDK team.
 * Adding a new feature module to the SDK requires a coordinated PR
 * touching this set — that PR is the formal record that the feature
 * passed governance review.
 *
 * **Defends against compile-time supply-chain injection.** A malicious
 * dependency that ships a `META-INF/services/...FeatureProvider`
 * descriptor pointing to its own class will be rejected by
 * [com.grinwich.sdk.contracts.Resolver.register] with
 * [com.grinwich.sdk.contracts.error.UnapprovedProviderException]
 * because its FQN is not on this list.
 *
 * The set includes [com.grinwich.sdk.contracts.SyntheticFeatureProvider]
 * itself because the wiring registers a synthetic instance for
 * `Context` + `SdkConfig` and that registration also passes through
 * the same allowlist gate. Uniform rule, no flavor-based bypass.
 */
internal object HApprovedProviders {
    val FQNS: Set<String> = setOf(
        // Synthetic — wiring constructs this directly with the caller's
        // Context + SdkConfig. Class is part of :di-contracts and just
        // republishes its constructor map; it cannot misbehave.
        "com.grinwich.sdk.contracts.SyntheticFeatureProvider",

        // Approved Dagger-flavor providers shipped by the SDK.
        "com.grinwich.sdk.feature.observability.ObservabilityProvider",
        "com.grinwich.sdk.feature.core.CoreProvider",
        "com.grinwich.sdk.feature.enc.EncProvider",
        "com.grinwich.sdk.feature.auth.AuthProvider",
        "com.grinwich.sdk.feature.stor.StorProvider",
        "com.grinwich.sdk.feature.ana.AnaProvider",
        "com.grinwich.sdk.feature.syn.SynProvider",
    )
}

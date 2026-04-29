package com.grinwich.sdk.daggerc

/**
 * Closed allowlist of [FeatureInitializer] FQNs that Pattern C
 * (`DaggerCSdk` + ServiceLoader) will accept during discovery.
 *
 * Same defence as `HApprovedProviders` (Pattern H) — a malicious JAR
 * shipping a `META-INF/services/com.grinwich.sdk.daggerc.FeatureInitializer`
 * descriptor that points to its own class is rejected at `discover()`
 * time because its FQN is not on this list. Adding a new feature
 * initializer requires a coordinated PR touching this set.
 *
 * **Note on the residual `featureName` hijack vector.** Two approved
 * initializers cannot collide (curated set), but the discovery map is
 * keyed by `featureName` (a String). If an unapproved initializer ever
 * slipped past this allowlist, `associateBy { it.featureName }` would
 * silently overwrite the legit binding. The FQN check below makes that
 * impossible at the source — the malicious class never reaches the map.
 */
internal object CApprovedInitializers {
    val FQNS: Set<String> = setOf(
        "com.grinwich.sdk.daggerc.EncryptionInit",
        "com.grinwich.sdk.daggerc.AuthInit",
        "com.grinwich.sdk.daggerc.StorageInit",
        "com.grinwich.sdk.daggerc.AnalyticsInit",
        "com.grinwich.sdk.daggerc.SyncInit",
    )
}

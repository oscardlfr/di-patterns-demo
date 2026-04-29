package com.grinwich.sdk.contracts.koin

/**
 * Closed allowlist of [KoinFeatureProvider] FQNs that the Koin-based
 * wirings will accept during discovery.
 *
 * Same defence as `HApprovedProviders` (Pattern H). The set is split
 * by discovery mechanism because the actual provider classes differ:
 *
 *  - [JAVA_SPI]: classes named `*KoinProvider`, contributed via
 *    `META-INF/services/...KoinFeatureProvider`. Consumed by Patterns
 *    L (eager) and M (lazy `loadModules`) using
 *    `java.util.ServiceLoader`.
 *  - [SWEET_SPI]: classes named `*SweetSpiProvider`, annotated with
 *    `@dev.whyoleg.sweetspi.ServiceProvider` and discovered at compile
 *    time. Consumed by Pattern N using
 *    `dev.whyoleg.sweetspi.ServiceLoader`.
 *
 * Both sets reach the same business behaviour through Koin modules,
 * but a malicious dependency cannot inject a provider through either
 * channel without being on the corresponding list. Adding a new
 * Koin feature requires a coordinated PR touching the relevant set.
 */
object KoinApprovedProviders {
    /** Approved FQNs for Patterns L and M (java.util.ServiceLoader). */
    val JAVA_SPI: Set<String> = setOf(
        "com.grinwich.sdk.feature.observability.ObservabilityKoinProvider",
        "com.grinwich.sdk.feature.enc.EncKoinProvider",
        "com.grinwich.sdk.feature.auth.AuthKoinProvider",
        "com.grinwich.sdk.feature.stor.StorKoinProvider",
        "com.grinwich.sdk.feature.ana.AnaKoinProvider",
        "com.grinwich.sdk.feature.syn.SynKoinProvider",
    )

    /** Approved FQNs for Pattern N (sweet-spi). */
    val SWEET_SPI: Set<String> = setOf(
        "com.grinwich.sdk.feature.observability.ObservabilitySweetSpiProvider",
        "com.grinwich.sdk.feature.enc.EncSweetSpiProvider",
        "com.grinwich.sdk.feature.auth.AuthSweetSpiProvider",
        "com.grinwich.sdk.feature.stor.StorSweetSpiProvider",
        "com.grinwich.sdk.feature.ana.AnaSweetSpiProvider",
        "com.grinwich.sdk.feature.syn.SynSweetSpiProvider",
    )
}

package com.grinwich.sdk.contracts

/**
 * Allowlist of provider FQNs that a discovery-based wiring is allowed
 * to register.
 *
 * **Threat addressed:** compile-time supply-chain injection. Any JAR
 * on the runtime classpath that contains an SPI descriptor
 * (`META-INF/services/...`) pointing to a class implementing the SPI
 * is loaded by `ServiceLoader` and instantiated reflectively during
 * `init()`. Without an allowlist, a malicious dependency can plant a
 * provider that runs arbitrary code, registers itself as
 * `EncryptionApi`/`AuthApi`/etc. or captures `Context`/`SdkConfig`.
 *
 * The allowlist is enumerated by FQN, not by interface or package, so
 * substitution attacks (declare your own class but implement the SPI)
 * cannot bypass it.
 *
 * **Reused across SPI types.** [isApproved] takes `Any` so the same
 * machinery covers every discovery-based pattern in the SDK:
 *
 *  - [FeatureProvider] — patterns H, I, J, K (Resolver).
 *  - `KoinFeatureProvider` (in :di-contracts-koin) — patterns L, M, N
 *    (Koin + ServiceLoader/sweet-spi).
 *  - `FeatureInitializer` — pattern C (DaggerCSdk + ServiceLoader).
 *
 * **Where to use:** production wirings construct a [strict] allowlist
 * of every approved provider class and either pass it to a
 * [Resolver]/registry that does the gating, or call [isApproved]
 * before registering each discovered provider. Tests use [OPEN] so
 * they can register fakes freely.
 *
 * **Maintenance:** adding a new feature module to the SDK requires
 * two coordinated PRs:
 *  - the new `feature-X-impl` module with its provider class,
 *  - an update to the production allowlist registering the new FQN.
 *
 * In a regulated context (e.g. banking) the second PR is the formal
 * record that the new feature was reviewed by SDK governance.
 */
class ProviderAllowlist private constructor(
    private val mode: Mode,
    private val approved: Set<String>,
) {

    private enum class Mode { STRICT, OPEN }

    /**
     * Returns `true` if [provider] is approved for registration.
     *
     * - In [strict] mode, the provider's `Class.name` must be in the
     *   approved set.
     * - In [OPEN] mode, every provider is approved.
     *
     * `provider` is typed as `Any` so the same allowlist works for
     * every SPI: [FeatureProvider], `KoinFeatureProvider`,
     * `FeatureInitializer`, etc.
     */
    fun isApproved(provider: Any): Boolean = when (mode) {
        Mode.OPEN -> true
        Mode.STRICT -> provider::class.java.name in approved
    }

    companion object {
        /**
         * Strict allowlist. Production wirings should always use this.
         *
         * @param approved the exhaustive set of FQNs allowed to register.
         *                 Include the synthetic provider class FQN so the
         *                 wiring's own `SyntheticFeatureProvider`
         *                 registrations pass the check too.
         */
        fun strict(approved: Set<String>): ProviderAllowlist =
            ProviderAllowlist(Mode.STRICT, approved.toSet())

        /**
         * Open allowlist — every provider passes. Use only in tests
         * (and as the [Resolver] default to keep existing call sites
         * source-compatible). Do **not** use in production.
         */
        @JvmField
        val OPEN: ProviderAllowlist = ProviderAllowlist(Mode.OPEN, emptySet())
    }
}

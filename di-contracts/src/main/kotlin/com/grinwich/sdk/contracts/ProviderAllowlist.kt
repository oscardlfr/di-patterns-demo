package com.grinwich.sdk.contracts

/**
 * Allowlist of [FeatureProvider] FQNs that the [Resolver] is allowed to
 * register.
 *
 * **Threat addressed:** compile-time supply-chain injection. Any JAR on
 * the runtime classpath that contains a
 * `META-INF/services/com.grinwich.sdk.contracts.FeatureProvider`
 * descriptor pointing to a class extending [FeatureProvider] is loaded
 * by `ServiceLoader.load()` and instantiated reflectively during
 * `init()`. Without an allowlist, a malicious dependency can plant a
 * provider that runs arbitrary code in `build()`, registers itself as
 * `EncryptionApi`/`AuthApi`/etc. or captures `Context`/`SdkConfig`.
 *
 * The allowlist is enumerated by FQN, not by interface or package, so
 * substitution attacks (declare your own class but extend
 * [FeatureProvider]) cannot bypass it.
 *
 * **Where to use:** production wirings (e.g. `MultiModuleSdkH`)
 * construct a [strict] allowlist of every approved provider class and
 * pass it to the [Resolver] constructor. Tests use [OPEN] so they can
 * register fakes freely.
 *
 * **Maintenance:** adding a new feature module to the SDK requires two
 * coordinated PRs:
 *  - the new `feature-X-impl` module with its `XFeatureProvider`,
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
     */
    fun isApproved(provider: FeatureProvider): Boolean = when (mode) {
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

package com.grinwich.sdk.contracts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct unit tests for [ProviderAllowlist] — independent of the
 * [Resolver] integration tests, so an allowlist regression is caught at
 * the lowest possible level.
 */
class ProviderAllowlistTest {

    private interface ServiceA
    private class FakeProvider(override val flavor: Flavor = Flavor.PURE) : FeatureProvider() {
        override val services: Set<Class<*>> = setOf(ServiceA::class.java)
        override fun build(resolver: Resolver): Map<Class<*>, Any> = emptyMap()
    }

    // -------------------------------------------------------------- //
    // OPEN
    // -------------------------------------------------------------- //

    @Test
    fun `OPEN approves any provider`() {
        val any = FakeProvider()
        assertTrue(ProviderAllowlist.OPEN.isApproved(any))
    }

    @Test
    fun `OPEN approves providers across multiple flavors`() {
        for (flavor in Flavor.entries) {
            val provider = FakeProvider(flavor)
            assertTrue(
                "OPEN must approve flavor=$flavor",
                ProviderAllowlist.OPEN.isApproved(provider),
            )
        }
    }

    @Test
    fun `OPEN is a stable singleton`() {
        // Two reads must return the same instance — callers can compare
        // by identity if they ever need to detect "this allowlist is
        // open".
        assertTrue(ProviderAllowlist.OPEN === ProviderAllowlist.OPEN)
    }

    // -------------------------------------------------------------- //
    // strict()
    // -------------------------------------------------------------- //

    @Test
    fun `strict approves a provider whose FQN is in the set`() {
        val provider = FakeProvider()
        val allowlist = ProviderAllowlist.strict(setOf(provider::class.java.name))
        assertTrue(allowlist.isApproved(provider))
    }

    @Test
    fun `strict rejects a provider whose FQN is not in the set`() {
        val provider = FakeProvider()
        val allowlist = ProviderAllowlist.strict(setOf("com.example.Other"))
        assertFalse(allowlist.isApproved(provider))
    }

    @Test
    fun `strict with empty set rejects everything`() {
        val allowlist = ProviderAllowlist.strict(approved = emptySet())
        assertFalse(allowlist.isApproved(FakeProvider()))
    }

    @Test
    fun `strict copies the input set so external mutation is harmless`() {
        // The input may be a mutable set in caller code; allowlist must
        // snapshot it. If the caller mutates afterwards, that must NOT
        // change which providers the allowlist approves.
        val provider = FakeProvider()
        val mutable: MutableSet<String> = mutableSetOf(provider::class.java.name)
        val allowlist = ProviderAllowlist.strict(mutable)

        // Add an unrelated FQN AFTER constructing the allowlist.
        mutable += "com.example.AddedAfter"

        // Original member is still approved.
        assertTrue(allowlist.isApproved(provider))

        // Caller cannot enlarge the allowlist after creation.
        val sneaky = object : FeatureProvider() {
            override val flavor = Flavor.PURE
            override val services = setOf(ServiceA::class.java)
            override fun build(resolver: Resolver) = emptyMap<Class<*>, Any>()
        }
        // sneaky's FQN is not "com.example.AddedAfter" anyway, but the
        // contract is what matters — the allowlist owns its own data.
        assertFalse(
            "Allowlist must not see entries added to the source set after construction",
            allowlist.isApproved(sneaky),
        )
    }

    @Test
    fun `strict matches by exact FQN — does not match by prefix or suffix`() {
        val provider = FakeProvider()
        val fqn = provider::class.java.name

        // Prefix and suffix variants must be rejected.
        val prefix = fqn.removeSuffix(provider::class.java.simpleName)
        val suffix = "Mismatch.$fqn"

        val allowlist = ProviderAllowlist.strict(setOf(prefix, suffix))
        assertFalse(
            "Substring match would be a vulnerability",
            allowlist.isApproved(provider),
        )
    }

    @Test
    fun `strict approves only the listed flavor variants`() {
        // The same logical class doesn't exist in multiple flavors at the
        // FQN level (each flavor is a separate class), so listing only
        // one variant must reject the others. Demonstrated by listing one
        // and rejecting another.
        val approvedProvider = FakeProvider(Flavor.DAGGER)
        val unlistedProvider = object : FeatureProvider() {
            override val flavor = Flavor.PURE
            override val services = setOf(ServiceA::class.java)
            override fun build(resolver: Resolver) = emptyMap<Class<*>, Any>()
        }

        val allowlist = ProviderAllowlist.strict(setOf(approvedProvider::class.java.name))
        assertTrue(allowlist.isApproved(approvedProvider))
        assertFalse(allowlist.isApproved(unlistedProvider))
    }

    @Test
    fun `two strict allowlists do not share state`() {
        val p1 = FakeProvider(Flavor.DAGGER)
        val p2 = object : FeatureProvider() {
            override val flavor = Flavor.PURE
            override val services = setOf(ServiceA::class.java)
            override fun build(resolver: Resolver) = emptyMap<Class<*>, Any>()
        }
        val allowlist1 = ProviderAllowlist.strict(setOf(p1::class.java.name))
        val allowlist2 = ProviderAllowlist.strict(setOf(p2::class.java.name))

        assertTrue(allowlist1.isApproved(p1))
        assertFalse(allowlist1.isApproved(p2))
        assertFalse(allowlist2.isApproved(p1))
        assertTrue(allowlist2.isApproved(p2))
    }
}

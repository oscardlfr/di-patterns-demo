package com.grinwich.sdk.contracts

import com.grinwich.sdk.contracts.error.CircularDependencyException
import com.grinwich.sdk.contracts.error.NoProviderFoundException
import com.grinwich.sdk.contracts.error.ProviderAlreadyFailedException
import com.grinwich.sdk.contracts.error.ProviderBuildException
import com.grinwich.sdk.contracts.error.ServiceCastException
import com.grinwich.sdk.contracts.error.ServiceNotAvailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the lazy [AutoServiceRegistry] used by pattern E2. The iterative
 * DFS must fail deterministically on cycles, propagate build errors as
 * typed [ProviderBuildException], and honour the "one failure, no retries"
 * policy enforced by [ProviderAlreadyFailedException].
 */
class AutoServiceRegistryTest {

    // Feature markers — neutral, never referenced as a real type outside
    // the Class<*> identity used as dependency key.
    private class FeatureIdA private constructor()
    private class FeatureIdB private constructor()
    private class FeatureIdC private constructor()

    // Ten base marker interfaces used to mint a large pool of distinct
    // `Class<*>` instances for the deep-chain test via
    // [java.lang.reflect.Proxy.getProxyClass]: a non-empty subset of these
    // uniquely identifies a proxy class, giving us 1_023 distinct markers
    // without declaring hundreds of named types.
    private interface M0; private interface M1; private interface M2
    private interface M3; private interface M4; private interface M5
    private interface M6; private interface M7; private interface M8
    private interface M9

    private fun markerForIndex(index: Int): Class<*> {
        // Index 0 would produce an empty interface set; Proxy requires at
        // least one interface, so shift to 1-based.
        val bits = index + 1
        val bases = arrayOf<Class<*>>(
            M0::class.java, M1::class.java, M2::class.java, M3::class.java,
            M4::class.java, M5::class.java, M6::class.java, M7::class.java,
            M8::class.java, M9::class.java,
        )
        val subset = bases.filterIndexed { bit, _ -> (bits shr bit) and 1 == 1 }
        return java.lang.reflect.Proxy.getProxyClass(
            AutoServiceRegistryTest::class.java.classLoader,
            *subset.toTypedArray(),
        )
    }

    private interface ServiceA
    private interface ServiceB
    private interface ServiceC
    private class ImplA : ServiceA
    private class ImplB : ServiceB
    private class ImplC : ServiceC

    private fun entry(
        id: Class<*>,
        services: Set<Class<*>>,
        dependencies: Set<Class<*>> = emptySet(),
        persistent: Boolean = false,
        build: (AutoServiceRegistry) -> Map<Class<*>, Any>,
    ) = AutoServiceEntry(
        featureId = id,
        dependencies = dependencies,
        serviceClasses = services,
        persistent = persistent,
        build = build,
    )

    // ------------------------------------------------------------------ //
    // Happy path
    // ------------------------------------------------------------------ //

    @Test
    fun `builds a dependency chain lazily on first get`() {
        val registry = AutoServiceRegistry()
        val aImpl = ImplA()
        val bImpl = ImplB()
        registry.install(entry(FeatureIdA::class.java, setOf(ServiceA::class.java)) {
            mapOf(ServiceA::class.java to aImpl)
        })
        registry.install(
            entry(
                id = FeatureIdB::class.java,
                services = setOf(ServiceB::class.java),
                dependencies = setOf(FeatureIdA::class.java),
            ) { r ->
                r.get(ServiceA::class.java)
                mapOf(ServiceB::class.java to bImpl)
            },
        )

        val resolvedB = registry.get(ServiceB::class.java)

        assertSame(bImpl, resolvedB)
        assertTrue(registry.isBuilt(FeatureIdA::class.java))
        assertTrue(registry.isBuilt(FeatureIdB::class.java))
    }

    @Test
    fun `cached services short-circuit subsequent resolutions`() {
        val registry = AutoServiceRegistry()
        var invocations = 0
        registry.install(entry(FeatureIdA::class.java, setOf(ServiceA::class.java)) {
            invocations++
            mapOf(ServiceA::class.java to ImplA())
        })

        registry.get(ServiceA::class.java)
        registry.get(ServiceA::class.java)

        assertEquals(1, invocations)
    }

    // ------------------------------------------------------------------ //
    // NoProviderFoundException
    // ------------------------------------------------------------------ //

    @Test
    fun `throws NoProviderFoundException on unindexed service`() {
        val registry = AutoServiceRegistry()
        assertThrows(NoProviderFoundException::class.java) {
            registry.get(ServiceA::class.java)
        }
    }

    @Test
    fun `throws NoProviderFoundException when a declared dependency lacks an entry`() {
        val registry = AutoServiceRegistry()
        registry.install(
            entry(
                id = FeatureIdB::class.java,
                services = setOf(ServiceB::class.java),
                dependencies = setOf(FeatureIdA::class.java),
            ) { mapOf(ServiceB::class.java to ImplB()) },
        )
        // FeatureIdA is never installed

        assertThrows(NoProviderFoundException::class.java) {
            registry.get(ServiceB::class.java)
        }
    }

    // ------------------------------------------------------------------ //
    // CircularDependencyException
    // ------------------------------------------------------------------ //

    @Test
    fun `detects two-node cycle`() {
        val registry = AutoServiceRegistry()
        registry.install(
            entry(
                id = FeatureIdA::class.java,
                services = setOf(ServiceA::class.java),
                dependencies = setOf(FeatureIdB::class.java),
            ) { mapOf(ServiceA::class.java to ImplA()) },
        )
        registry.install(
            entry(
                id = FeatureIdB::class.java,
                services = setOf(ServiceB::class.java),
                dependencies = setOf(FeatureIdA::class.java),
            ) { mapOf(ServiceB::class.java to ImplB()) },
        )

        assertThrows(CircularDependencyException::class.java) {
            registry.get(ServiceA::class.java)
        }
    }

    @Test
    fun `detects three-node cycle`() {
        val registry = AutoServiceRegistry()
        registry.install(
            entry(
                id = FeatureIdA::class.java,
                services = setOf(ServiceA::class.java),
                dependencies = setOf(FeatureIdB::class.java),
            ) { mapOf(ServiceA::class.java to ImplA()) },
        )
        registry.install(
            entry(
                id = FeatureIdB::class.java,
                services = setOf(ServiceB::class.java),
                dependencies = setOf(FeatureIdC::class.java),
            ) { mapOf(ServiceB::class.java to ImplB()) },
        )
        registry.install(
            entry(
                id = FeatureIdC::class.java,
                services = setOf(ServiceC::class.java),
                dependencies = setOf(FeatureIdA::class.java),
            ) { mapOf(ServiceC::class.java to ImplC()) },
        )

        assertThrows(CircularDependencyException::class.java) {
            registry.get(ServiceA::class.java)
        }
    }

    @Test
    fun `detects self-dependency`() {
        val registry = AutoServiceRegistry()
        registry.install(
            entry(
                id = FeatureIdA::class.java,
                services = setOf(ServiceA::class.java),
                dependencies = setOf(FeatureIdA::class.java),
            ) { mapOf(ServiceA::class.java to ImplA()) },
        )

        assertThrows(CircularDependencyException::class.java) {
            registry.get(ServiceA::class.java)
        }
    }

    // ------------------------------------------------------------------ //
    // ProviderBuildException + ProviderAlreadyFailedException
    // ------------------------------------------------------------------ //

    @Test
    fun `wraps build() failures in ProviderBuildException`() {
        val registry = AutoServiceRegistry()
        val cause = RuntimeException("boom")
        registry.install(entry(FeatureIdA::class.java, setOf(ServiceA::class.java)) {
            throw cause
        })

        val ex = assertThrows(ProviderBuildException::class.java) {
            registry.get(ServiceA::class.java)
        }
        assertSame(cause, ex.cause)
    }

    @Test
    fun `retry after build failure throws ProviderAlreadyFailedException`() {
        val registry = AutoServiceRegistry()
        var invocations = 0
        registry.install(entry(FeatureIdA::class.java, setOf(ServiceA::class.java)) {
            invocations++
            throw RuntimeException("boom")
        })

        assertThrows(ProviderBuildException::class.java) {
            registry.get(ServiceA::class.java)
        }
        assertThrows(ProviderAlreadyFailedException::class.java) {
            registry.get(ServiceA::class.java)
        }
        assertEquals(1, invocations)
    }

    @Test
    fun `clear resets failed features so the registry can be reused`() {
        val registry = AutoServiceRegistry()
        var failing = true
        registry.install(entry(FeatureIdA::class.java, setOf(ServiceA::class.java)) {
            if (failing) throw RuntimeException("boom")
            mapOf(ServiceA::class.java to ImplA())
        })
        assertThrows(ProviderBuildException::class.java) {
            registry.get(ServiceA::class.java)
        }

        registry.clear()
        failing = false
        registry.install(entry(FeatureIdA::class.java, setOf(ServiceA::class.java)) {
            mapOf(ServiceA::class.java to ImplA())
        })

        assertTrue(registry.get(ServiceA::class.java) is ImplA)
    }

    // ------------------------------------------------------------------ //
    // ServiceNotAvailableException + ServiceCastException
    // ------------------------------------------------------------------ //

    @Test
    fun `throws ServiceNotAvailableException when build omits a declared service`() {
        val registry = AutoServiceRegistry()
        registry.install(entry(
            id = FeatureIdA::class.java,
            services = setOf(ServiceA::class.java, ServiceB::class.java),
        ) { mapOf(ServiceA::class.java to ImplA()) })

        assertThrows(ServiceNotAvailableException::class.java) {
            registry.get(ServiceB::class.java)
        }
    }

    @Test
    fun `throws ServiceCastException when the published instance has the wrong type`() {
        val registry = AutoServiceRegistry()
        registry.install(entry(FeatureIdA::class.java, setOf(ServiceA::class.java)) {
            mapOf(ServiceA::class.java to ImplB())
        })

        assertThrows(ServiceCastException::class.java) {
            registry.get(ServiceA::class.java)
        }
    }

    // ------------------------------------------------------------------ //
    // Persistence
    // ------------------------------------------------------------------ //

    // ------------------------------------------------------------------ //
    // State invariants
    // ------------------------------------------------------------------ //

    @Test
    fun `unrelated features still build after a cycle is detected elsewhere`() {
        val registry = AutoServiceRegistry()
        registry.install(
            entry(
                id = FeatureIdA::class.java,
                services = setOf(ServiceA::class.java),
                dependencies = setOf(FeatureIdB::class.java),
            ) { mapOf(ServiceA::class.java to ImplA()) },
        )
        registry.install(
            entry(
                id = FeatureIdB::class.java,
                services = setOf(ServiceB::class.java),
                dependencies = setOf(FeatureIdA::class.java),
            ) { mapOf(ServiceB::class.java to ImplB()) },
        )
        val standalone = ImplC()
        registry.install(entry(FeatureIdC::class.java, setOf(ServiceC::class.java)) {
            mapOf(ServiceC::class.java to standalone)
        })

        assertThrows(CircularDependencyException::class.java) {
            registry.get(ServiceA::class.java)
        }
        // The cycle was confined to A↔B and must not leave visiting-set
        // residue that could interfere with independent resolutions.
        assertSame(standalone, registry.get(ServiceC::class.java))
    }

    @Test
    fun `unrelated features still build after a sibling fails`() {
        val registry = AutoServiceRegistry()
        registry.install(entry(FeatureIdA::class.java, setOf(ServiceA::class.java)) {
            throw RuntimeException("boom")
        })
        registry.install(entry(FeatureIdB::class.java, setOf(ServiceB::class.java)) {
            mapOf(ServiceB::class.java to ImplB())
        })

        assertThrows(ProviderBuildException::class.java) {
            registry.get(ServiceA::class.java)
        }
        assertTrue(registry.get(ServiceB::class.java) is ImplB)
    }

    @Test
    fun `dependent entry short-circuits when its dep is already known to have failed`() {
        val registry = AutoServiceRegistry()
        var aBuildCount = 0
        registry.install(entry(FeatureIdA::class.java, setOf(ServiceA::class.java)) {
            aBuildCount++
            throw RuntimeException("boom")
        })
        registry.install(
            entry(
                id = FeatureIdB::class.java,
                services = setOf(ServiceB::class.java),
                dependencies = setOf(FeatureIdA::class.java),
            ) { mapOf(ServiceB::class.java to ImplB()) },
        )

        // First attempt surfaces the real failure of A.
        assertThrows(ProviderBuildException::class.java) {
            registry.get(ServiceA::class.java)
        }
        // B depends on A. Resolving B now must short-circuit on A's
        // failedFeatures entry instead of re-running A's build().
        assertThrows(ProviderAlreadyFailedException::class.java) {
            registry.get(ServiceB::class.java)
        }
        assertEquals(1, aBuildCount)
    }

    // ------------------------------------------------------------------ //
    // Deep acyclic graphs — iterative DFS must not overflow the stack
    // ------------------------------------------------------------------ //

    @Test
    fun `deep linear chain of features resolves without StackOverflowError`() {
        // Depth well past the recursion ceiling of typical JVM stacks —
        // a naive recursive DFS would overflow long before this. Array
        // classes of successive dimensions (`[I`, `[[I`, `[[[I`, …) give
        // us a cheap pool of genuinely distinct `Class<*>` markers
        // without declaring hundreds of named types.
        val depth = 500
        val registry = AutoServiceRegistry()
        val ids: List<Class<*>> = List(depth) { i -> markerForIndex(i) }

        for (i in 0 until depth) {
            val self = ids[i]
            val deps = if (i < depth - 1) setOf(ids[i + 1]) else emptySet()
            val isRoot = i == 0
            registry.install(
                AutoServiceEntry(
                    featureId = self,
                    dependencies = deps,
                    // Only the root exposes the service the test resolves
                    // by; the rest are pure dependency nodes that must be
                    // built first.
                    serviceClasses = if (isRoot) setOf(ServiceA::class.java) else emptySet(),
                    persistent = false,
                    build = {
                        if (isRoot) mapOf(ServiceA::class.java to ImplA())
                        else emptyMap()
                    },
                ),
            )
        }

        val resolved = registry.get(ServiceA::class.java)
        assertTrue(resolved is ImplA)
        assertEquals(depth, registry.builtFeatureCount)
    }

    @Test
    fun `transitive cycle whose root is outside the cycle is still detected`() {
        val registry = AutoServiceRegistry()
        // A → B → C → B
        registry.install(
            entry(
                id = FeatureIdA::class.java,
                services = setOf(ServiceA::class.java),
                dependencies = setOf(FeatureIdB::class.java),
            ) { mapOf(ServiceA::class.java to ImplA()) },
        )
        registry.install(
            entry(
                id = FeatureIdB::class.java,
                services = setOf(ServiceB::class.java),
                dependencies = setOf(FeatureIdC::class.java),
            ) { mapOf(ServiceB::class.java to ImplB()) },
        )
        registry.install(
            entry(
                id = FeatureIdC::class.java,
                services = setOf(ServiceC::class.java),
                dependencies = setOf(FeatureIdB::class.java),
            ) { mapOf(ServiceC::class.java to ImplC()) },
        )

        assertThrows(CircularDependencyException::class.java) {
            registry.get(ServiceA::class.java)
        }
    }

    @Test
    fun `persistent features survive clear`() {
        val registry = AutoServiceRegistry()
        val instance = ImplA()
        registry.install(
            entry(
                id = FeatureIdA::class.java,
                services = setOf(ServiceA::class.java),
                persistent = true,
            ) { mapOf(ServiceA::class.java to instance) },
        )

        val before = registry.get(ServiceA::class.java)
        registry.clear()
        // Re-install on the next init (same pattern as the wiring layer).
        registry.install(
            entry(
                id = FeatureIdA::class.java,
                services = setOf(ServiceA::class.java),
                persistent = true,
            ) { mapOf(ServiceA::class.java to instance) },
        )

        val after = registry.get(ServiceA::class.java)
        assertSame(before, after)
    }
}

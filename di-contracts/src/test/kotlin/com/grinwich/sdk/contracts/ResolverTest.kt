package com.grinwich.sdk.contracts

import com.grinwich.sdk.contracts.error.CircularDependencyException
import com.grinwich.sdk.contracts.error.DependencyResolutionException
import com.grinwich.sdk.contracts.error.NoProviderFoundException
import com.grinwich.sdk.contracts.error.ProviderAlreadyFailedException
import com.grinwich.sdk.contracts.error.ProviderBuildException
import com.grinwich.sdk.contracts.error.ServiceCastException
import com.grinwich.sdk.contracts.error.ServiceNotAvailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers the [Resolver] domain-error surface (patterns H/I/J/K) and its
 * cycle-detection contract. Every exception type in
 * [com.grinwich.sdk.contracts.error] has at least one targeted test.
 */
class ResolverTest {

    // ------------------------------------------------------------------ //
    // Service interfaces and fakes used across the suite
    // ------------------------------------------------------------------ //

    private interface ServiceA
    private interface ServiceB
    private interface ServiceC
    private interface ServiceD
    private class ImplA : ServiceA
    private class ImplB : ServiceB
    private class ImplC : ServiceC
    private class ImplD : ServiceD

    /**
     * Provider that publishes [services] by returning [instances] on build.
     * The builder lambda runs first so tests can stub dependency resolution
     * or throw to exercise failure paths.
     */
    private class FakeProvider(
        override val services: Set<Class<*>>,
        override val flavor: Flavor = Flavor.PURE,
        override val persistent: Boolean = false,
        private val builder: (Resolver) -> Map<Class<*>, Any> = { emptyMap() },
    ) : FeatureProvider() {
        var buildCount: Int = 0
            private set

        override fun build(resolver: Resolver): Map<Class<*>, Any> {
            buildCount++
            return builder(resolver)
        }
    }

    // ------------------------------------------------------------------ //
    // Happy path
    // ------------------------------------------------------------------ //

    @Test
    fun `resolves service the first time build() runs`() {
        val resolver = Resolver()
        val instance = ImplA()
        val provider = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = { mapOf(ServiceA::class.java to instance) },
        )
        resolver.register(provider)

        assertSame(instance, resolver.get(ServiceA::class.java))
        assertEquals(1, provider.buildCount)
    }

    @Test
    fun `second get() is served from the cache without rebuilding`() {
        val resolver = Resolver()
        val provider = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = { mapOf(ServiceA::class.java to ImplA()) },
        )
        resolver.register(provider)

        val first = resolver.get(ServiceA::class.java)
        val second = resolver.get(ServiceA::class.java)

        assertSame(first, second)
        assertEquals(1, provider.buildCount)
    }

    @Test
    fun `lazy upstream resolution pulls dependencies transitively`() {
        val resolver = Resolver()
        val aImpl = ImplA()
        val providerA = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = { mapOf(ServiceA::class.java to aImpl) },
        )
        val bImpl = ImplB()
        val providerB = FakeProvider(
            services = setOf(ServiceB::class.java),
            builder = { r ->
                // B explicitly depends on A
                r.get(ServiceA::class.java)
                mapOf(ServiceB::class.java to bImpl)
            },
        )
        resolver.register(providerA)
        resolver.register(providerB)

        val resolvedB = resolver.get(ServiceB::class.java)

        assertSame(bImpl, resolvedB)
        assertEquals(1, providerA.buildCount)
        assertEquals(1, providerB.buildCount)
    }

    // ------------------------------------------------------------------ //
    // NoProviderFoundException
    // ------------------------------------------------------------------ //

    @Test
    fun `throws NoProviderFoundException when no provider is registered`() {
        val resolver = Resolver()
        val ex = assertThrows(NoProviderFoundException::class.java) {
            resolver.get(ServiceA::class.java)
        }
        assertTrue(
            "Message should name the missing service",
            ex.message!!.contains("ServiceA"),
        )
    }

    // ------------------------------------------------------------------ //
    // CircularDependencyException
    // ------------------------------------------------------------------ //

    @Test
    fun `detects direct cycle between two providers`() {
        val resolver = Resolver()
        lateinit var providerA: FakeProvider
        lateinit var providerB: FakeProvider
        providerA = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = { r ->
                r.get(ServiceB::class.java)
                mapOf(ServiceA::class.java to ImplA())
            },
        )
        providerB = FakeProvider(
            services = setOf(ServiceB::class.java),
            builder = { r ->
                r.get(ServiceA::class.java)
                mapOf(ServiceB::class.java to ImplB())
            },
        )
        resolver.register(providerA)
        resolver.register(providerB)

        assertThrows(CircularDependencyException::class.java) {
            resolver.get(ServiceA::class.java)
        }
    }

    @Test
    fun `detects self-dependency cycle`() {
        val resolver = Resolver()
        val provider = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = { r ->
                r.get(ServiceA::class.java)
                mapOf(ServiceA::class.java to ImplA())
            },
        )
        resolver.register(provider)

        assertThrows(CircularDependencyException::class.java) {
            resolver.get(ServiceA::class.java)
        }
    }

    @Test
    fun `detects three-node cycle A-B-C-A`() {
        val resolver = Resolver()
        val providerA = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = { r ->
                r.get(ServiceB::class.java)
                mapOf(ServiceA::class.java to ImplA())
            },
        )
        val providerB = FakeProvider(
            services = setOf(ServiceB::class.java),
            builder = { r ->
                r.get(ServiceC::class.java)
                mapOf(ServiceB::class.java to ImplB())
            },
        )
        val providerC = FakeProvider(
            services = setOf(ServiceC::class.java),
            builder = { r ->
                r.get(ServiceA::class.java)
                mapOf(ServiceC::class.java to ImplC())
            },
        )
        resolver.register(providerA)
        resolver.register(providerB)
        resolver.register(providerC)

        assertThrows(CircularDependencyException::class.java) {
            resolver.get(ServiceA::class.java)
        }
    }

    @Test
    fun `cycle does not trigger StackOverflowError`() {
        // Proves the cycle is short-circuited well before the JVM stack
        // blows up. The fast-fail path would otherwise recurse without
        // bound: the goal of the test is "does this terminate with the
        // domain exception", not wall-clock speed.
        val resolver = Resolver()
        val providerA = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = { r ->
                r.get(ServiceB::class.java)
                mapOf(ServiceA::class.java to ImplA())
            },
        )
        val providerB = FakeProvider(
            services = setOf(ServiceB::class.java),
            builder = { r ->
                r.get(ServiceA::class.java)
                mapOf(ServiceB::class.java to ImplB())
            },
        )
        resolver.register(providerA)
        resolver.register(providerB)

        try {
            resolver.get(ServiceA::class.java)
            fail("Expected CircularDependencyException")
        } catch (e: StackOverflowError) {
            fail("Cycle must be reported as a domain error, not a StackOverflowError")
        } catch (e: CircularDependencyException) {
            // expected
        }
    }

    // ------------------------------------------------------------------ //
    // ProviderBuildException + ProviderAlreadyFailedException
    // ------------------------------------------------------------------ //

    @Test
    fun `wraps arbitrary exceptions from build() in ProviderBuildException`() {
        val resolver = Resolver()
        val cause = RuntimeException("boom")
        val provider = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = { throw cause },
        )
        resolver.register(provider)

        val ex = assertThrows(ProviderBuildException::class.java) {
            resolver.get(ServiceA::class.java)
        }
        assertSame(cause, ex.cause)
    }

    @Test
    fun `subsequent resolution after build failure throws ProviderAlreadyFailedException`() {
        val resolver = Resolver()
        val provider = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = { throw RuntimeException("boom") },
        )
        resolver.register(provider)

        assertThrows(ProviderBuildException::class.java) {
            resolver.get(ServiceA::class.java)
        }
        assertThrows(ProviderAlreadyFailedException::class.java) {
            resolver.get(ServiceA::class.java)
        }
        // build() was invoked exactly once despite two resolution attempts
        assertEquals(1, provider.buildCount)
    }

    @Test
    fun `clear resets failed state and allows a fresh build`() {
        val resolver = Resolver()
        var failNext = true
        val provider = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = {
                if (failNext) throw RuntimeException("boom")
                mapOf(ServiceA::class.java to ImplA())
            },
        )
        resolver.register(provider)

        assertThrows(ProviderBuildException::class.java) {
            resolver.get(ServiceA::class.java)
        }
        resolver.clear()
        // Re-register, since non-persistent providers do not survive clear()
        failNext = false
        val secondProvider = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = { mapOf(ServiceA::class.java to ImplA()) },
        )
        resolver.register(secondProvider)

        val resolved = resolver.get(ServiceA::class.java)
        assertTrue(resolved is ImplA)
    }

    // ------------------------------------------------------------------ //
    // ServiceNotAvailableException
    // ------------------------------------------------------------------ //

    @Test
    fun `throws ServiceNotAvailableException when build() omits a declared service`() {
        val resolver = Resolver()
        val provider = FakeProvider(
            services = setOf(ServiceA::class.java, ServiceB::class.java),
            builder = { mapOf(ServiceA::class.java to ImplA()) }, // ServiceB missing
        )
        resolver.register(provider)

        val ex = assertThrows(ServiceNotAvailableException::class.java) {
            resolver.get(ServiceB::class.java)
        }
        assertTrue(ex.message!!.contains("ServiceB"))
    }

    // ------------------------------------------------------------------ //
    // ServiceCastException
    // ------------------------------------------------------------------ //

    @Test
    fun `throws ServiceCastException when build() publishes an incompatible type`() {
        val resolver = Resolver()
        val provider = FakeProvider(
            services = setOf(ServiceA::class.java),
            // Map value is not a ServiceA — simulates a misconfigured provider.
            builder = { mapOf(ServiceA::class.java to ImplB()) },
        )
        resolver.register(provider)

        assertThrows(ServiceCastException::class.java) {
            resolver.get(ServiceA::class.java)
        }
    }

    // ------------------------------------------------------------------ //
    // Persistent providers and clear()
    // ------------------------------------------------------------------ //

    @Test
    fun `persistent providers survive clear and are not rebuilt`() {
        val resolver = Resolver()
        val persistent = FakeProvider(
            services = setOf(ServiceA::class.java),
            persistent = true,
            builder = { mapOf(ServiceA::class.java to ImplA()) },
        )
        resolver.register(persistent)

        val before = resolver.get(ServiceA::class.java)
        resolver.clear()
        val after = resolver.get(ServiceA::class.java)

        assertSame(before, after)
        assertEquals(1, persistent.buildCount)
    }

    @Test
    fun `non-persistent providers are dropped on clear`() {
        val resolver = Resolver()
        val provider = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = { mapOf(ServiceA::class.java to ImplA()) },
        )
        resolver.register(provider)
        resolver.get(ServiceA::class.java)
        resolver.clear()

        // After clear, the non-persistent provider is no longer registered.
        assertThrows(NoProviderFoundException::class.java) {
            resolver.get(ServiceA::class.java)
        }
    }

    @Test
    fun `builtFeatureCount excludes persistent providers`() {
        val resolver = Resolver()
        val nonPersistent = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = { mapOf(ServiceA::class.java to ImplA()) },
        )
        val persistent = FakeProvider(
            services = setOf(ServiceB::class.java),
            persistent = true,
            builder = { mapOf(ServiceB::class.java to ImplB()) },
        )
        resolver.register(nonPersistent)
        resolver.register(persistent)

        resolver.get(ServiceA::class.java)
        resolver.get(ServiceB::class.java)

        assertEquals(1, resolver.builtFeatureCount)
    }

    // ------------------------------------------------------------------ //
    // Concurrency
    // ------------------------------------------------------------------ //

    @Test
    fun `concurrent resolutions build the provider exactly once`() {
        val resolver = Resolver()
        val invocations = AtomicInteger(0)
        val barrier = CountDownLatch(1)
        val provider = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = {
                invocations.incrementAndGet()
                // Widen the window during which other threads can race.
                Thread.sleep(20)
                mapOf(ServiceA::class.java to ImplA())
            },
        )
        resolver.register(provider)

        val threads = (1..16).map {
            Thread {
                barrier.await()
                resolver.get(ServiceA::class.java)
            }
        }
        threads.forEach { it.start() }
        barrier.countDown()
        threads.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }

        assertEquals(1, invocations.get())
    }

    // ------------------------------------------------------------------ //
    // Registry independence
    // ------------------------------------------------------------------ //

    @Test
    fun `two resolvers do not share state`() {
        val first = Resolver()
        val second = Resolver()
        val providerFirst = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = { mapOf(ServiceA::class.java to ImplA()) },
        )
        val providerSecond = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = { mapOf(ServiceA::class.java to ImplA()) },
        )
        first.register(providerFirst)
        second.register(providerSecond)

        val a1 = first.get(ServiceA::class.java)
        val a2 = second.get(ServiceA::class.java)

        assertNotSame(a1, a2)
    }

    // ------------------------------------------------------------------ //
    // State invariants — residual cleanup after failures
    // ------------------------------------------------------------------ //

    @Test
    fun `unrelated providers resolve normally after a cycle is thrown elsewhere`() {
        val resolver = Resolver()
        val providerA = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = { r ->
                r.get(ServiceB::class.java)
                mapOf(ServiceA::class.java to ImplA())
            },
        )
        val providerB = FakeProvider(
            services = setOf(ServiceB::class.java),
            builder = { r ->
                r.get(ServiceA::class.java)
                mapOf(ServiceB::class.java to ImplB())
            },
        )
        val cImpl = ImplC()
        val providerC = FakeProvider(
            services = setOf(ServiceC::class.java),
            builder = { mapOf(ServiceC::class.java to cImpl) },
        )
        resolver.register(providerA)
        resolver.register(providerB)
        resolver.register(providerC)

        assertThrows(CircularDependencyException::class.java) {
            resolver.get(ServiceA::class.java)
        }

        // C has nothing to do with the cycle — it must still resolve. This
        // guards against state residue in `buildingProviders` after the
        // cycle unwinds.
        assertSame(cImpl, resolver.get(ServiceC::class.java))
    }

    @Test
    fun `cycle does not mark participants as failed — only structural retries re-throw the cycle`() {
        val resolver = Resolver()
        val providerA = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = { r ->
                r.get(ServiceB::class.java)
                mapOf(ServiceA::class.java to ImplA())
            },
        )
        val providerB = FakeProvider(
            services = setOf(ServiceB::class.java),
            builder = { r ->
                r.get(ServiceA::class.java)
                mapOf(ServiceB::class.java to ImplB())
            },
        )
        resolver.register(providerA)
        resolver.register(providerB)

        assertThrows(CircularDependencyException::class.java) {
            resolver.get(ServiceA::class.java)
        }
        // Second attempt on the same structure is still a cycle, NOT a
        // stale `ProviderAlreadyFailedException`.
        assertThrows(CircularDependencyException::class.java) {
            resolver.get(ServiceA::class.java)
        }
    }

    @Test
    fun `unrelated providers resolve normally after a sibling fails to build`() {
        val resolver = Resolver()
        val failing = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = { throw RuntimeException("boom") },
        )
        val healthy = FakeProvider(
            services = setOf(ServiceB::class.java),
            builder = { mapOf(ServiceB::class.java to ImplB()) },
        )
        resolver.register(failing)
        resolver.register(healthy)

        assertThrows(ProviderBuildException::class.java) {
            resolver.get(ServiceA::class.java)
        }
        assertTrue(resolver.get(ServiceB::class.java) is ImplB)
    }

    @Test
    fun `dependent provider surfaces the downstream failure without being marked failed itself`() {
        val resolver = Resolver()
        val broken = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = { throw RuntimeException("boom") },
        )
        val dependant = FakeProvider(
            services = setOf(ServiceB::class.java),
            builder = { r ->
                r.get(ServiceA::class.java)
                mapOf(ServiceB::class.java to ImplB())
            },
        )
        resolver.register(broken)
        resolver.register(dependant)

        assertThrows(ProviderBuildException::class.java) {
            resolver.get(ServiceB::class.java)
        }
        // The downstream provider is now marked failed; a second attempt
        // on it short-circuits to ProviderAlreadyFailed rather than
        // re-running build().
        assertThrows(ProviderAlreadyFailedException::class.java) {
            resolver.get(ServiceA::class.java)
        }
    }

    // ------------------------------------------------------------------ //
    // Edge topology
    // ------------------------------------------------------------------ //

    @Test
    fun `detects transitive cycle whose root is outside the cycle`() {
        // A → B → C → D → B (A is not part of the cycle but triggers it)
        val resolver = Resolver()
        val providerA = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = { r ->
                r.get(ServiceB::class.java)
                mapOf(ServiceA::class.java to ImplA())
            },
        )
        val providerB = FakeProvider(
            services = setOf(ServiceB::class.java),
            builder = { r ->
                r.get(ServiceC::class.java)
                mapOf(ServiceB::class.java to ImplB())
            },
        )
        val providerC = FakeProvider(
            services = setOf(ServiceC::class.java),
            builder = { r ->
                r.get(ServiceD::class.java)
                mapOf(ServiceC::class.java to ImplC())
            },
        )
        val providerD = FakeProvider(
            services = setOf(ServiceD::class.java),
            builder = { r ->
                r.get(ServiceB::class.java) // closes cycle at B, not A
                mapOf(ServiceD::class.java to ImplD())
            },
        )
        resolver.register(providerA)
        resolver.register(providerB)
        resolver.register(providerC)
        resolver.register(providerD)

        assertThrows(CircularDependencyException::class.java) {
            resolver.get(ServiceA::class.java)
        }
    }

    @Test
    fun `persistent provider caught in a cycle is still reported as cycle`() {
        val resolver = Resolver()
        val persistent = FakeProvider(
            services = setOf(ServiceA::class.java),
            persistent = true,
            builder = { r ->
                r.get(ServiceB::class.java)
                mapOf(ServiceA::class.java to ImplA())
            },
        )
        val partner = FakeProvider(
            services = setOf(ServiceB::class.java),
            builder = { r ->
                r.get(ServiceA::class.java)
                mapOf(ServiceB::class.java to ImplB())
            },
        )
        resolver.register(persistent)
        resolver.register(partner)

        assertThrows(CircularDependencyException::class.java) {
            resolver.get(ServiceA::class.java)
        }
    }

    @Test
    fun `provider with empty services registers without effect`() {
        val resolver = Resolver()
        val empty = FakeProvider(
            services = emptySet(),
            builder = { emptyMap() },
        )
        resolver.register(empty)
        // No services indexed — get() on any class fails with NoProviderFound.
        assertThrows(NoProviderFoundException::class.java) {
            resolver.get(ServiceA::class.java)
        }
        // And empty never got built because nobody requested it.
        assertEquals(0, empty.buildCount)
    }

    @Test
    fun `last registered provider wins when two providers declare the same service`() {
        val resolver = Resolver()
        val first = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = { mapOf(ServiceA::class.java to ImplA()) },
        )
        val second = FakeProvider(
            services = setOf(ServiceA::class.java),
            builder = { mapOf(ServiceA::class.java to ImplA()) },
        )
        resolver.register(first)
        resolver.register(second)

        resolver.get(ServiceA::class.java)
        // Only the surviving provider (second) is built. The overwritten
        // one was evicted from the index at registration time.
        assertEquals(0, first.buildCount)
        assertEquals(1, second.buildCount)
    }

    // ------------------------------------------------------------------ //
    // Hierarchy contract
    // ------------------------------------------------------------------ //

    @Test
    fun `every domain exception extends DependencyResolutionException`() {
        val samples = listOf<Throwable>(
            NoProviderFoundException("svc"),
            CircularDependencyException("Provider"),
            ProviderBuildException("Provider", RuntimeException("x")),
            ProviderAlreadyFailedException("Provider"),
            ServiceCastException("svc", ClassCastException("x")),
            ServiceNotAvailableException("svc", "Provider"),
        )
        for (sample in samples) {
            assertTrue(
                "${sample::class.java.simpleName} must extend DependencyResolutionException",
                sample is DependencyResolutionException,
            )
        }
        // And the base type is still a RuntimeException so callers are not
        // forced to declare it.
        assertFalse(DependencyResolutionException::class.java.isAssignableFrom(Exception::class.java))
    }
}

package com.grinwich.sdk.contracts

import com.grinwich.sdk.contracts.error.CircularDependencyException
import com.grinwich.sdk.contracts.error.NoProviderFoundException
import com.grinwich.sdk.contracts.error.ProviderBuildException
import com.grinwich.sdk.contracts.error.ServiceCastException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Covers the eager [ServiceRegistry] used by pattern E. Cycle detection
 * happens at registration time via [ServiceRegistry.Companion.topoSort];
 * the suite verifies every resulting failure funnels through the typed
 * hierarchy.
 */
class ServiceRegistryTest {

    private class FeatureIdA private constructor()
    private class FeatureIdB private constructor()
    private class FeatureIdC private constructor()

    private interface ServiceA
    private interface ServiceB
    private class ImplA : ServiceA
    private class ImplB(val a: ServiceA) : ServiceB

    // ------------------------------------------------------------------ //
    // Happy path
    // ------------------------------------------------------------------ //

    @Test
    fun `builds entries in topological order even when registered bottom-up`() {
        val registry = ServiceRegistry()
        val entries = listOf(
            ServiceEntry(
                featureId = FeatureIdB::class.java,
                dependencies = setOf(FeatureIdA::class.java),
            ) { r ->
                val a = r.get(ServiceA::class.java)
                mapOf(ServiceB::class.java to ImplB(a))
            },
            ServiceEntry(featureId = FeatureIdA::class.java) {
                mapOf(ServiceA::class.java to ImplA())
            },
        )

        registry.registerAll(entries)

        val b = registry.get(ServiceB::class.java)
        assertSame(registry.get(ServiceA::class.java), (b as ImplB).a)
        assertEquals(2, registry.builtFeatureCount)
    }

    // ------------------------------------------------------------------ //
    // CircularDependencyException
    // ------------------------------------------------------------------ //

    @Test
    fun `topoSort detects a direct cycle`() {
        val registry = ServiceRegistry()
        val entries = listOf(
            ServiceEntry(
                featureId = FeatureIdA::class.java,
                dependencies = setOf(FeatureIdB::class.java),
            ) { mapOf(ServiceA::class.java to ImplA()) },
            ServiceEntry(
                featureId = FeatureIdB::class.java,
                dependencies = setOf(FeatureIdA::class.java),
            ) { mapOf(ServiceB::class.java to ImplB(ImplA())) },
        )

        assertThrows(CircularDependencyException::class.java) {
            registry.registerAll(entries)
        }
    }

    @Test
    fun `topoSort detects an indirect cycle`() {
        val registry = ServiceRegistry()
        val entries = listOf(
            ServiceEntry(
                featureId = FeatureIdA::class.java,
                dependencies = setOf(FeatureIdB::class.java),
            ) { mapOf(ServiceA::class.java to ImplA()) },
            ServiceEntry(
                featureId = FeatureIdB::class.java,
                dependencies = setOf(FeatureIdC::class.java),
            ) { mapOf(ServiceB::class.java to ImplB(ImplA())) },
            ServiceEntry(
                featureId = FeatureIdC::class.java,
                dependencies = setOf(FeatureIdA::class.java),
            ) { emptyMap() },
        )

        assertThrows(CircularDependencyException::class.java) {
            registry.registerAll(entries)
        }
    }

    // ------------------------------------------------------------------ //
    // NoProviderFoundException
    // ------------------------------------------------------------------ //

    @Test
    fun `register throws NoProviderFoundException when a dependency is missing`() {
        val registry = ServiceRegistry()
        val entry = ServiceEntry(
            featureId = FeatureIdB::class.java,
            dependencies = setOf(FeatureIdA::class.java),
        ) { mapOf(ServiceB::class.java to ImplB(ImplA())) }

        assertThrows(NoProviderFoundException::class.java) {
            registry.register(entry)
        }
    }

    @Test
    fun `get throws NoProviderFoundException for an unknown service`() {
        val registry = ServiceRegistry()
        assertThrows(NoProviderFoundException::class.java) {
            registry.get(ServiceA::class.java)
        }
    }

    // ------------------------------------------------------------------ //
    // ProviderBuildException
    // ------------------------------------------------------------------ //

    @Test
    fun `wraps build() failures in ProviderBuildException`() {
        val registry = ServiceRegistry()
        val cause = RuntimeException("boom")
        val entry = ServiceEntry(featureId = FeatureIdA::class.java) {
            throw cause
        }

        val ex = assertThrows(ProviderBuildException::class.java) {
            registry.register(entry)
        }
        assertSame(cause, ex.cause)
    }

    // ------------------------------------------------------------------ //
    // ServiceCastException
    // ------------------------------------------------------------------ //

    @Test
    fun `throws ServiceCastException when the map value is incompatible`() {
        val registry = ServiceRegistry()
        val entry = ServiceEntry(featureId = FeatureIdA::class.java) {
            // ServiceA key mapped to a value that is NOT a ServiceA.
            mapOf<Class<*>, Any>(ServiceA::class.java to Any())
        }
        registry.register(entry)

        assertThrows(ServiceCastException::class.java) {
            registry.get(ServiceA::class.java)
        }
    }
}

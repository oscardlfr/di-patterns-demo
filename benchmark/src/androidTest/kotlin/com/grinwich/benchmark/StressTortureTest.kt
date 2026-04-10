package com.grinwich.benchmark

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grinwich.sdk.api.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Torture tests — pushes every lazy wiring pattern to its absolute limits.
 *
 * All test logic written ONCE, executed across 6 patterns via [MultiModuleSdkApi].
 */
@RunWith(AndroidJUnit4::class)
class StressTortureTest {

    private val config = SdkConfig(debug = false)
    private val tag = "TORTURE"

    @After
    fun tearDown() {
        ALL_LAZY_SDKS.forEach { (_, sdk) -> sdk.shutdown() }
    }

    // ════════════════════════════════════════════════════════
    // 1. THUNDERING HERD — 100 threads, CyclicBarrier
    // ════════════════════════════════════════════════════════

    @Test fun thunderingHerd_D() = thunderingHerd("D")
    @Test fun thunderingHerd_E2() = thunderingHerd("E2")
    @Test fun thunderingHerd_G() = thunderingHerd("G")
    @Test fun thunderingHerd_H() = thunderingHerd("H")
    @Test fun thunderingHerd_I() = thunderingHerd("I")
    @Test fun thunderingHerd_J() = thunderingHerd("J")
    @Test fun thunderingHerd_K() = thunderingHerd("K")

    /**
     * THUNDERING HERD: 100 threads llaman get<EncryptionApi>() al MISMO instante.
     *
     * Usa CyclicBarrier — un punto de sincronizacion donde N threads esperan
     * hasta que TODOS han llegado, y entonces se desbloquean simultaneamente.
     *
     * Sin CyclicBarrier: los threads arrancan uno a uno. El primero termina
     * antes de que el ultimo empiece. No hay contention real.
     *
     * Con CyclicBarrier(100): los 100 threads llaman barrier.await() y se
     * bloquean. Cuando el thread #100 llega, los 100 se desbloquean y
     * ejecutan sdk.get() en el MISMO nanosegundo. Esto maximiza la
     * probabilidad de race conditions en el Resolver.
     *
     * CopyOnWriteArrayList: lista thread-safe para recoger resultados.
     * Una ArrayList normal corromperia su estado interno si 100 threads
     * llaman add() simultaneamente.
     *
     * Verifica:
     * - 0 errores (el Resolver no lanza excepciones bajo contention)
     * - 100 resultados (ningun thread se quedo sin respuesta)
     * - Todos assertSame (singleton — todos reciben la MISMA instancia)
     */
    private fun thunderingHerd(name: String) {
        val sdk = sdkByName(name)
        sdk.init(testContext, config)
        sdk.get(SyncApi::class.java)
        sdk.get(AnalyticsApi::class.java)

        val threadCount = 100
        // CyclicBarrier: todos los threads esperan aqui hasta que los 100 han llegado.
        // Cuando el ultimo llega, se desbloquean todos simultaneamente.
        val barrier = CyclicBarrier(threadCount)
        // CopyOnWriteArrayList: thread-safe para escrituras concurrentes.
        val results = CopyOnWriteArrayList<EncryptionApi>()
        val errors = CopyOnWriteArrayList<Throwable>()

        val threads = (1..threadCount).map {
            Thread {
                try {
                    barrier.await()  // BLOQUEA hasta que los 100 threads llegan aqui
                    results.add(sdk.get(EncryptionApi::class.java))  // los 100 ejecutan esto AL MISMO TIEMPO
                } catch (e: Throwable) { errors.add(e) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }  // espera max 5 segundos a que todos terminen

        assertTrue("$name: errors in herd: ${errors.map { it.message }}", errors.isEmpty())
        assertEquals("$name: all threads got result", threadCount, results.size)
        val ref = results[0]
        results.forEach { assertSame("$name: singleton violated", ref, it) }
    }

    // ════════════════════════════════════════════════════════
    // 2. SINGLETON IDENTITY — 10K calls
    // ════════════════════════════════════════════════════════

    @Test fun singleton_D() = singletonIdentity("D")
    @Test fun singleton_E2() = singletonIdentity("E2")
    @Test fun singleton_G() = singletonIdentity("G")
    @Test fun singleton_H() = singletonIdentity("H")
    @Test fun singleton_I() = singletonIdentity("I")
    @Test fun singleton_J() = singletonIdentity("J")
    @Test fun singleton_K() = singletonIdentity("K")

    private fun singletonIdentity(name: String) {
        val sdk = sdkByName(name)
        sdk.init(testContext, config)
        val first = sdk.get(EncryptionApi::class.java)
        repeat(10_000) { i ->
            assertSame("$name: singleton violated at $i", first, sdk.get(EncryptionApi::class.java))
        }
    }

    // ════════════════════════════════════════════════════════
    // 3. CROSS-PATTERN ISOLATION — all 6 alive simultaneously
    // ════════════════════════════════════════════════════════

    @Test
    fun crossPatternIsolation() {
        // Init all 6
        ALL_LAZY_SDKS.forEach { (_, sdk) -> sdk.init(testContext, config) }

        // Get Enc from all — must be different instances
        val encs = ALL_LAZY_SDKS.map { (name, sdk) ->
            name to sdk.get(EncryptionApi::class.java)
        }
        for (i in encs.indices) {
            for (j in i + 1 until encs.size) {
                assertNotSame(
                    "${encs[i].first} and ${encs[j].first} share instance",
                    encs[i].second, encs[j].second
                )
            }
        }

        // Shutdown one — others survive
        val (firstName, firstSdk) = ALL_LAZY_SDKS[0]
        firstSdk.shutdown()
        assertEquals("$firstName shutdown", 0, firstSdk.builtProvisionCount)
        for ((name, sdk) in ALL_LAZY_SDKS.drop(1)) {
            assertTrue("$name survives ${firstName}'s shutdown", sdk.builtProvisionCount > 0)
        }
    }

    // ════════════════════════════════════════════════════════
    // 4. RAPID FIRE — 5K init/get/shutdown cycles
    // ════════════════════════════════════════════════════════

    @Test fun rapidFire_D() = rapidFire("D")
    @Test fun rapidFire_E2() = rapidFire("E2")
    @Test fun rapidFire_G() = rapidFire("G")
    @Test fun rapidFire_H() = rapidFire("H")
    @Test fun rapidFire_I() = rapidFire("I")
    @Test fun rapidFire_J() = rapidFire("J")
    @Test fun rapidFire_K() = rapidFire("K")

    private fun rapidFire(name: String) {
        val sdk = sdkByName(name)
        val expected = EXPECTED_COUNTS[name]!!
        repeat(5000) { i ->
            sdk.init(testContext, config)
            assertEquals("$name cycle $i: after init", expected.afterInit, sdk.builtProvisionCount)
            sdk.get(EncryptionApi::class.java)
            assertEquals("$name cycle $i: after get<Enc>", expected.afterEnc, sdk.builtProvisionCount)
            sdk.shutdown()
            assertEquals("$name cycle $i: after shutdown", 0, sdk.builtProvisionCount)
        }
    }

    // ════════════════════════════════════════════════════════
    // 5. MEMORY PRESSURE — GC storm mid-resolution
    // ════════════════════════════════════════════════════════

    @Test fun memoryPressure_D() = memoryPressure("D")
    @Test fun memoryPressure_E2() = memoryPressure("E2")
    @Test fun memoryPressure_G() = memoryPressure("G")
    @Test fun memoryPressure_H() = memoryPressure("H")
    @Test fun memoryPressure_I() = memoryPressure("I")
    @Test fun memoryPressure_J() = memoryPressure("J")
    @Test fun memoryPressure_K() = memoryPressure("K")

    /**
     * MEMORY PRESSURE: verifica que las provisions sobreviven un GC storm.
     *
     * El GC (Garbage Collector) recoge automaticamente objetos que ya no
     * tienen referencias. Si el Resolver guardara provisions con WeakReference,
     * el GC las recogeria y get() devolveria una instancia diferente (rota).
     *
     * Este test fuerza al GC a ejecutarse agresivamente:
     * 1. Construye EncryptionApi (provision en el Resolver)
     * 2. Crea 5 MB de basura (5x ByteArray de 1 MB) para presionar al GC
     * 3. Llama System.gc() + Thread.yield() entre cada allocation
     *    (yield cede CPU al GC thread para que pueda recoger la basura)
     * 4. Verifica que la provision sigue viva (assertSame = misma instancia)
     * 5. Verifica que el DFS sigue funcionando (construir Sync tras el GC storm)
     *
     * Pasa porque el Resolver usa ConcurrentHashMap con strong references —
     * el GC no puede recoger objetos que estan en un HashMap.
     */
    private fun memoryPressure(name: String) {
        val sdk = sdkByName(name)
        val expected = EXPECTED_COUNTS[name]!!
        sdk.init(testContext, config)
        val enc = sdk.get(EncryptionApi::class.java)
        assertEquals(expected.afterEnc, sdk.builtProvisionCount)

        // GC STORM: crear 5 MB de basura para forzar al GC a ejecutarse.
        // ByteArray(1_000_000) = 1 MB de basura que se descarta inmediatamente.
        // System.gc() + Thread.yield() entre cada allocation para dar oportunidad
        // al GC de recoger basura Y intentar recoger nuestras provisions.
        repeat(5) { System.gc(); Thread.yield(); @Suppress("UNUSED_VARIABLE") val g = ByteArray(1_000_000) }
        System.gc(); Thread.sleep(100)  // pausa final para que el GC complete

        // Las provisions DEBEN sobrevivir — strong refs en ConcurrentHashMap
        assertEquals("$name: provisions survive GC", expected.afterEnc, sdk.builtProvisionCount)
        assertSame("$name: same instance after GC", enc, sdk.get(EncryptionApi::class.java))

        // El DFS debe seguir funcionando despues del GC storm
        sdk.get(SyncApi::class.java)
        assertEquals("$name: cascade after GC", expected.afterSync, sdk.builtProvisionCount)
    }

    // ════════════════════════════════════════════════════════
    // 6. 10K CYCLE STRESS — extreme leak detection
    // ════════════════════════════════════════════════════════

    @Test fun stress10K_D() = stress10K("D")
    @Test fun stress10K_E2() = stress10K("E2")
    @Test fun stress10K_G() = stress10K("G")
    @Test fun stress10K_H() = stress10K("H")
    @Test fun stress10K_I() = stress10K("I")
    @Test fun stress10K_J() = stress10K("J")
    @Test fun stress10K_K() = stress10K("K")

    private fun stress10K(name: String) {
        val sdk = sdkByName(name)
        val expected = EXPECTED_COUNTS[name]!!
        repeat(100) { sdk.init(testContext, config); sdk.get(SyncApi::class.java); sdk.shutdown() }

        forceGc()
        val heapBefore = usedHeapKb()
        val pssInfoBefore = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }

        repeat(10_000) {
            sdk.init(testContext, config)
            sdk.get(SyncApi::class.java)
            sdk.get(AnalyticsApi::class.java)
            assertEquals(expected.fullGraph, sdk.builtProvisionCount)
            sdk.shutdown()
        }

        forceGc()
        val heapAfter = usedHeapKb()
        val pssInfoAfter = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }

        val heapDelta = heapAfter - heapBefore
        val pssDelta = pssInfoAfter.totalPss - pssInfoBefore.totalPss

        Log.d(tag, "$name 10K: heap=$heapDelta KB, pss=$pssDelta KB")
        assertTrue("$name: heap leak ($heapDelta KB)", heapDelta < 5120)
    }

    // ════════════════════════════════════════════════════════
    // 7. INSTANCE FRESHNESS — 50 reinits, all unique
    // ════════════════════════════════════════════════════════

    @Test fun freshness_D() = instanceFreshness("D")
    @Test fun freshness_E2() = instanceFreshness("E2")
    @Test fun freshness_G() = instanceFreshness("G")
    @Test fun freshness_H() = instanceFreshness("H")
    @Test fun freshness_I() = instanceFreshness("I")
    @Test fun freshness_J() = instanceFreshness("J")
    @Test fun freshness_K() = instanceFreshness("K")

    private fun instanceFreshness(name: String) {
        val sdk = sdkByName(name)
        val ids = mutableSetOf<Int>()
        repeat(50) { cycle ->
            sdk.init(testContext, config)
            val enc = sdk.get(EncryptionApi::class.java)
            val id = System.identityHashCode(enc)
            assertFalse("$name cycle $cycle: reused instance", ids.contains(id))
            ids.add(id)
            sdk.shutdown()
        }
        assertEquals("$name: 50 unique instances", 50, ids.size)
    }

    // ════════════════════════════════════════════════════════
    // 8. ERROR RESILIENCE — double init, get before/after, double shutdown
    // ════════════════════════════════════════════════════════

    @Test fun errorResilience_D() = errorResilience("D")
    @Test fun errorResilience_E2() = errorResilience("E2")
    @Test fun errorResilience_G() = errorResilience("G")
    @Test fun errorResilience_H() = errorResilience("H")
    @Test fun errorResilience_I() = errorResilience("I")
    @Test fun errorResilience_J() = errorResilience("J")
    @Test fun errorResilience_K() = errorResilience("K")

    private fun errorResilience(name: String) {
        val sdk = sdkByName(name)

        // Double init throws
        sdk.init(testContext, config)
        try { sdk.init(testContext, config); fail("$name: double init should throw") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("already initialized")) }
        sdk.shutdown()

        // Get before init throws
        try { sdk.get(EncryptionApi::class.java); fail("$name: get before init should throw") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("not initialized")) }

        // Get after shutdown throws
        sdk.init(testContext, config); sdk.get(EncryptionApi::class.java); sdk.shutdown()
        try { sdk.get(EncryptionApi::class.java); fail("$name: get after shutdown should throw") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("not initialized")) }

        // Double shutdown is safe
        sdk.init(testContext, config); sdk.shutdown(); sdk.shutdown()
        assertEquals(0, sdk.builtProvisionCount)

        // Init after shutdown works
        sdk.init(testContext, config)
        assertNotNull(sdk.get(EncryptionApi::class.java))
        sdk.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // 9. FUNCTIONAL CORRECTNESS — after 1000 reinits
    // ════════════════════════════════════════════════════════

    @Test fun functional_D() = functionalCorrectness("D")
    @Test fun functional_E2() = functionalCorrectness("E2")
    @Test fun functional_G() = functionalCorrectness("G")
    @Test fun functional_H() = functionalCorrectness("H")
    @Test fun functional_I() = functionalCorrectness("I")
    @Test fun functional_J() = functionalCorrectness("J")
    @Test fun functional_K() = functionalCorrectness("K")

    private fun functionalCorrectness(name: String) {
        val sdk = sdkByName(name)
        repeat(1000) { sdk.init(testContext, config); sdk.shutdown() }

        sdk.init(testContext, config)
        val enc = sdk.get(EncryptionApi::class.java)
        val encrypted = enc.encrypt("secret")
        assertTrue("$name: encrypt works", encrypted.isNotEmpty())

        val auth = sdk.get(AuthApi::class.java)
        assertFalse("$name: not auth initially", auth.isAuthenticated())
        auth.login("user", "pass")
        assertTrue("$name: auth after login", auth.isAuthenticated())

        val sync = sdk.get(SyncApi::class.java)
        val result = runBlocking { sync.sync() }
        assertNotNull("$name: sync works", result)
    }

    // ════════════════════════════════════════════════════════
    // 10. COLD CASCADE TIMING — nanosecond comparison
    // ════════════════════════════════════════════════════════

    @Test
    fun coldCascadeTiming_allPatterns() {
        data class Timing(val name: String, val initUs: Long, val buildUs: Long, val cachedUs: Long)
        val timings = mutableListOf<Timing>()

        for ((name, sdk) in ALL_LAZY_SDKS) {
            val t0 = System.nanoTime()
            sdk.init(testContext, config)
            val t1 = System.nanoTime()
            sdk.get(SyncApi::class.java); sdk.get(AnalyticsApi::class.java)
            val t2 = System.nanoTime()
            repeat(1000) { sdk.get(EncryptionApi::class.java) }
            val t3 = System.nanoTime()
            timings.add(Timing(name, (t1 - t0) / 1000, (t2 - t1) / 1000, (t3 - t2) / 1000))
            sdk.shutdown()
        }

        Log.d(tag, "╔═══════╦══════════╦════════════╦═══════════════════╗")
        Log.d(tag, "║Pattern║ init µs  ║ build µs   ║ 1000x cached µs   ║")
        Log.d(tag, "╠═══════╬══════════╬════════════╬═══════════════════╣")
        for (t in timings) {
            Log.d(tag, "║ ${t.name.padEnd(5)} ║ ${"%8d".format(t.initUs)} ║ ${"%10d".format(t.buildUs)} ║ ${"%10d".format(t.cachedUs)}         ║")
        }
        Log.d(tag, "╚═══════╩══════════╩════════════╩═══════════════════╝")
    }

    // ════════════════════════════════════════════════════════
    // 11. CONCURRENT BUILD — multiple threads trigger build on empty graph
    // ════════════════════════════════════════════════════════

    @Test fun concurrentBuild_D() = concurrentBuild("D")
    @Test fun concurrentBuild_E2() = concurrentBuild("E2")
    @Test fun concurrentBuild_G() = concurrentBuild("G")
    @Test fun concurrentBuild_H() = concurrentBuild("H")
    @Test fun concurrentBuild_I() = concurrentBuild("I")
    @Test fun concurrentBuild_J() = concurrentBuild("J")
    @Test fun concurrentBuild_K() = concurrentBuild("K")

    private fun concurrentBuild(name: String) {
        val sdk = sdkByName(name)
        val expected = EXPECTED_COUNTS[name]!!

        repeat(100) { round ->
            sdk.init(testContext, config)

            // Graph is EMPTY — no services built yet.
            // Launch 6 threads, each requesting a different API simultaneously.
            // This forces concurrent build() calls in the Resolver.
            val errors = CopyOnWriteArrayList<Throwable>()
            val barrier = java.util.concurrent.CyclicBarrier(6)
            val results = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()

            val threads = listOf(
                Thread { try { barrier.await(); results[EncryptionApi::class.java] = sdk.get(EncryptionApi::class.java) } catch (e: Throwable) { errors.add(e) } },
                Thread { try { barrier.await(); results[AuthApi::class.java] = sdk.get(AuthApi::class.java) } catch (e: Throwable) { errors.add(e) } },
                Thread { try { barrier.await(); results[StorageApi::class.java] = sdk.get(StorageApi::class.java) } catch (e: Throwable) { errors.add(e) } },
                Thread { try { barrier.await(); results[AnalyticsApi::class.java] = sdk.get(AnalyticsApi::class.java) } catch (e: Throwable) { errors.add(e) } },
                Thread { try { barrier.await(); results[SyncApi::class.java] = sdk.get(SyncApi::class.java) } catch (e: Throwable) { errors.add(e) } },
                Thread { try { barrier.await(); results[HashApi::class.java] = sdk.get(HashApi::class.java) } catch (e: Throwable) { errors.add(e) } },
            )
            threads.forEach { it.start() }
            threads.forEach { it.join(10_000) }

            assertTrue("$name round $round: errors: ${errors.map { it.message }}", errors.isEmpty())
            assertEquals("$name round $round: all 6 services resolved", 6, results.size)
            assertEquals("$name round $round: full graph built", expected.fullGraph, sdk.builtProvisionCount)

            // Verify singletons — calling again must return same instances
            val enc1 = results[EncryptionApi::class.java]
            val enc2 = sdk.get(EncryptionApi::class.java)
            assertSame("$name round $round: singleton after concurrent build", enc1, enc2)

            sdk.shutdown()
        }
    }

    // ════════════════════════════════════════════════════════
    // 12. CONCURRENT SELECTIVE — laziness under contention
    //     3 threads request partial graph simultaneously.
    //     Verifies that unrequested features are NOT built.
    // ════════════════════════════════════════════════════════

    @Test fun concurrentSelective_D() = concurrentSelective("D")
    @Test fun concurrentSelective_E2() = concurrentSelective("E2")
    @Test fun concurrentSelective_G() = concurrentSelective("G")
    @Test fun concurrentSelective_H() = concurrentSelective("H")
    @Test fun concurrentSelective_I() = concurrentSelective("I")
    @Test fun concurrentSelective_J() = concurrentSelective("J")
    @Test fun concurrentSelective_K() = concurrentSelective("K")

    private fun concurrentSelective(name: String) {
        val sdk = sdkByName(name)
        val expected = EXPECTED_COUNTS[name]!!

        repeat(50) { round ->
            sdk.init(testContext, config)

            // 3 threads request PARTIAL graph: Enc, Ana, Auth.
            // Sync and Storage should NOT be built.
            val errors = CopyOnWriteArrayList<Throwable>()
            val barrier = java.util.concurrent.CyclicBarrier(3)

            val threads = listOf(
                Thread { try { barrier.await(); sdk.get(EncryptionApi::class.java) } catch (e: Throwable) { errors.add(e) } },
                Thread { try { barrier.await(); sdk.get(AnalyticsApi::class.java) } catch (e: Throwable) { errors.add(e) } },
                Thread { try { barrier.await(); sdk.get(AuthApi::class.java) } catch (e: Throwable) { errors.add(e) } },
            )
            threads.forEach { it.start() }
            threads.forEach { it.join(10_000) }

            assertTrue("$name round $round: errors: ${errors.map { it.message }}", errors.isEmpty())

            // Enc needs Core+Obs, Ana needs Core+Obs, Auth needs Core+Obs+Enc.
            // Sync and Storage must NOT be built — laziness preserved under concurrency.
            val count = sdk.builtProvisionCount
            assertTrue(
                "$name round $round: too many provisions ($count), Sync/Stor leaked into partial graph",
                count <= expected.afterSync // afterSync is the max with Enc+Auth+Ana but without full graph
            )

            // Verify specific services work
            assertNotNull("$name round $round: enc", sdk.get(EncryptionApi::class.java))
            assertNotNull("$name round $round: ana", sdk.get(AnalyticsApi::class.java))
            assertNotNull("$name round $round: auth", sdk.get(AuthApi::class.java))

            sdk.shutdown()
        }
    }

    // ════════════════════════════════════════════════════════
    // 13. CONCURRENT SHUTDOWN — get() during shutdown
    //     One thread shuts down while another is resolving.
    //     Must not crash with NPE or corrupt state.
    // ════════════════════════════════════════════════════════

    @Test fun concurrentShutdown_D() = concurrentShutdown("D")
    @Test fun concurrentShutdown_E2() = concurrentShutdown("E2")
    @Test fun concurrentShutdown_G() = concurrentShutdown("G")
    @Test fun concurrentShutdown_H() = concurrentShutdown("H")
    @Test fun concurrentShutdown_I() = concurrentShutdown("I")
    @Test fun concurrentShutdown_J() = concurrentShutdown("J")
    @Test fun concurrentShutdown_K() = concurrentShutdown("K")

    private fun concurrentShutdown(name: String) {
        val sdk = sdkByName(name)
        val crashCount = AtomicInteger(0)

        repeat(200) {
            sdk.init(testContext, config)
            sdk.get(EncryptionApi::class.java) // warm graph

            val latch = CountDownLatch(2)

            // Thread 1: tries to resolve services
            val reader = Thread {
                try {
                    sdk.get(EncryptionApi::class.java)
                    sdk.get(AuthApi::class.java)
                } catch (_: IllegalStateException) {
                    // Expected: "not initialized" if shutdown won the race
                } catch (e: Throwable) {
                    // Unexpected: NPE, ConcurrentModificationException, etc.
                    crashCount.incrementAndGet()
                } finally { latch.countDown() }
            }

            // Thread 2: shuts down the SDK
            val killer = Thread {
                try { sdk.shutdown() }
                catch (_: Throwable) { crashCount.incrementAndGet() }
                finally { latch.countDown() }
            }

            reader.start(); killer.start()
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            sdk.shutdown() // ensure clean state for next iteration
        }

        assertEquals("$name: unexpected crashes during concurrent shutdown", 0, crashCount.get())
    }

    // ════════════════════════════════════════════════════════
    // 14. ALTERNATING PATTERNS — zero cross-contamination
    // ════════════════════════════════════════════════════════

    @Test
    fun alternatingPatterns() {
        repeat(100) {
            for ((name, sdk) in ALL_LAZY_SDKS) {
                sdk.init(testContext, config)
                val enc = sdk.get(EncryptionApi::class.java)
                assertNotNull("$name: enc not null in alternation", enc)
                sdk.shutdown()
                assertEquals("$name: clean after shutdown", 0, sdk.builtProvisionCount)
            }
        }
    }

    // ════════════════════════════════════════════════════════
    // 15. LOGGER PERSISTENCE — same instance across shutdown/reinit
    // ════════════════════════════════════════════════════════

    @Test fun loggerPersistence_D() = loggerPersistence("D")
    @Test fun loggerPersistence_E2() = loggerPersistence("E2")
    @Test fun loggerPersistence_G() = loggerPersistence("G")
    @Test fun loggerPersistence_H() = loggerPersistence("H")
    @Test fun loggerPersistence_I() = loggerPersistence("I")
    @Test fun loggerPersistence_J() = loggerPersistence("J")
    @Test fun loggerPersistence_K() = loggerPersistence("K")

    /**
     * Verifica que el logger (ObservabilityProvisions) sobrevive shutdown/reinit.
     *
     * El logger esta marcado como persistent=true en los patrones que usan Resolver
     * (H/I/J/K). En D/G el logger es un campo del object (sobrevive por diseno).
     * En E2 el logger se pasa como parametro al registry (sobrevive por diseno).
     *
     * Para H/I/J/K: assertSame(logger1, logger2) — misma instancia tras reinit.
     * Para D/G: el logger es _logger field, siempre la misma instancia.
     * Para E2: el logger es _logger field pasado a allAutoEntries, misma instancia.
     */
    private fun loggerPersistence(name: String) {
        val sdk = sdkByName(name)
        sdk.init(testContext, config)
        val logger1 = sdk.get(SdkLogger::class.java)
        sdk.shutdown()
        sdk.init(testContext, config)
        val logger2 = sdk.get(SdkLogger::class.java)
        assertSame("$name: logger must persist across shutdown/reinit", logger1, logger2)
        sdk.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // 16. CONTEXT PERSISTENCE — same instance across shutdown/reinit
    //     Only for H/I/J/K (Resolver-based with persistent ContextProvisions).
    // ════════════════════════════════════════════════════════

    @Test fun contextPersistence_H() = contextPersistence("H")
    @Test fun contextPersistence_I() = contextPersistence("I")
    @Test fun contextPersistence_J() = contextPersistence("J")
    @Test fun contextPersistence_K() = contextPersistence("K")

    /**
     * Verifica que el Context (ContextProvisions) sobrevive shutdown/reinit.
     *
     * ContextProvisions esta marcado como persistent=true en el Resolver.
     * El applicationContext vive tanto como el proceso — no tiene sentido
     * destruirlo y reconstruirlo en cada reinit.
     */
    private fun contextPersistence(name: String) {
        val sdk = sdkByName(name)
        sdk.init(testContext, config)
        val ctx1 = sdk.get(android.content.Context::class.java)
        sdk.shutdown()
        sdk.init(testContext, config)
        val ctx2 = sdk.get(android.content.Context::class.java)
        assertSame("$name: context must persist across shutdown/reinit", ctx1, ctx2)
        sdk.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════

    private fun sdkByName(name: String): MultiModuleSdkApi =
        ALL_LAZY_SDKS.first { it.first == name }.second

    /**
     * Fuerza la ejecucion del Garbage Collector.
     *
     * Version mas agresiva que la de MemoryBehaviorTest (3x gc + 100ms sleep)
     * porque los stress tests generan mas basura (10,000 ciclos de objetos).
     *
     * System.gc() = sugerencia a la JVM/ART para recoger objetos sin referencia.
     * Thread.yield() = cede CPU al GC thread para que pueda ejecutarse.
     * Thread.sleep(100) = pausa 100ms para dar tiempo al GC a completar.
     *
     * Llamamos 3 veces porque ART (Android Runtime) a veces necesita multiples
     * pasadas para recoger objetos con referencias circulares o weak refs.
     */
    private fun forceGc() {
        System.gc(); Thread.yield(); System.gc(); Thread.sleep(100); System.gc()
    }

    /**
     * Mide memoria heap usada en KB.
     *
     * HEAP = memoria donde viven los objetos Java/Kotlin.
     * totalMemory() - freeMemory() = bytes ocupados por objetos vivos.
     *
     * En stress10K medimos heap ANTES y DESPUES de 10,000 ciclos.
     * Si el delta > 5,120 KB, hay un memory leak (objetos que deberian
     * haberse liberado en shutdown() pero alguien los retiene).
     */
    private fun usedHeapKb(): Long {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / 1024
    }
}

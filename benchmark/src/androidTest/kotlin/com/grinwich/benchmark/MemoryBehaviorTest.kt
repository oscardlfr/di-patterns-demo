package com.grinwich.benchmark

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grinwich.sdk.api.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Memory behavior tests — proves lazy instantiation across all 16 lazy patterns.
 *
 * Deterministic assertion tests (not benchmarks). Each test category is written
 * ONCE and executed for all patterns via [MultiModuleSdkApi].
 */
@RunWith(AndroidJUnit4::class)
class MemoryBehaviorTest {

    @get:Rule
    val patternFilter = PatternFilterRule()

    private val config = SdkConfig(debug = false)

    @After
    fun tearDown() {
        ALL_LAZY_SDKS.forEach { (_, sdk) -> sdk.shutdown() }
    }

    // ════════════════════════════════════════════════════════
    // 1. PROOF OF LAZINESS — init builds minimal provisions
    // ════════════════════════════════════════════════════════

    @Test fun initOnly_D() = assertInitOnly("D")
    @Test fun initOnly_E2() = assertInitOnly("E2")
    @Test fun initOnly_G() = assertInitOnly("G")
    @Test fun initOnly_H() = assertInitOnly("H")
    @Test fun initOnly_I() = assertInitOnly("I")
    @Test fun initOnly_J() = assertInitOnly("J")
    @Test fun initOnly_K() = assertInitOnly("K")
    @Test fun initOnly_L() = assertInitOnly("L")
    @Test fun initOnly_M() = assertInitOnly("M")
    @Test fun initOnly_N() = assertInitOnly("N")
    @Test fun initOnly_O() = assertInitOnly("O")
    @Test fun initOnly_P() = assertInitOnly("P")
    @Test fun initOnly_Q() = assertInitOnly("Q")
    @Test fun initOnly_O2() = assertInitOnly("O2")
    @Test fun initOnly_P2() = assertInitOnly("P2")
    @Test fun initOnly_Q2() = assertInitOnly("Q2")

    private fun assertInitOnly(name: String) {
        val sdk = sdkByName(name)
        val expected = EXPECTED_COUNTS[name]!!
        sdk.init(testContext, config)
        assertEquals("$name: after init", expected.afterInit, sdk.builtProvisionCount)
    }

    // ════════════════════════════════════════════════════════
    // 2. SELECTIVE RESOLUTION — get<Enc> builds only deps
    // ════════════════════════════════════════════════════════

    @Test fun getEnc_D() = assertGetEnc("D")
    @Test fun getEnc_E2() = assertGetEnc("E2")
    @Test fun getEnc_G() = assertGetEnc("G")
    @Test fun getEnc_H() = assertGetEnc("H")
    @Test fun getEnc_I() = assertGetEnc("I")
    @Test fun getEnc_J() = assertGetEnc("J")
    @Test fun getEnc_K() = assertGetEnc("K")
    @Test fun getEnc_L() = assertGetEnc("L")
    @Test fun getEnc_M() = assertGetEnc("M")
    @Test fun getEnc_N() = assertGetEnc("N")
    @Test fun getEnc_O() = assertGetEnc("O")
    @Test fun getEnc_P() = assertGetEnc("P")
    @Test fun getEnc_Q() = assertGetEnc("Q")
    @Test fun getEnc_O2() = assertGetEnc("O2")
    @Test fun getEnc_P2() = assertGetEnc("P2")
    @Test fun getEnc_Q2() = assertGetEnc("Q2")

    private fun assertGetEnc(name: String) {
        val sdk = sdkByName(name)
        val expected = EXPECTED_COUNTS[name]!!
        sdk.init(testContext, config)
        sdk.get(EncryptionApi::class.java)
        assertEquals("$name: after get<Enc>", expected.afterEnc, sdk.builtProvisionCount)
    }

    // ════════════════════════════════════════════════════════
    // 3. SELECTIVE RESOLUTION — get<Ana> doesn't build Auth/Stor/Syn
    // ════════════════════════════════════════════════════════

    @Test fun getAna_D() = assertGetAna("D")
    @Test fun getAna_E2() = assertGetAna("E2")
    @Test fun getAna_G() = assertGetAna("G")
    @Test fun getAna_H() = assertGetAna("H")
    @Test fun getAna_I() = assertGetAna("I")
    @Test fun getAna_J() = assertGetAna("J")
    @Test fun getAna_K() = assertGetAna("K")
    @Test fun getAna_L() = assertGetAna("L")
    @Test fun getAna_M() = assertGetAna("M")
    @Test fun getAna_N() = assertGetAna("N")
    @Test fun getAna_O() = assertGetAna("O")
    @Test fun getAna_P() = assertGetAna("P")
    @Test fun getAna_Q() = assertGetAna("Q")
    @Test fun getAna_O2() = assertGetAna("O2")
    @Test fun getAna_P2() = assertGetAna("P2")
    @Test fun getAna_Q2() = assertGetAna("Q2")

    private fun assertGetAna(name: String) {
        val sdk = sdkByName(name)
        val expected = EXPECTED_COUNTS[name]!!
        sdk.init(testContext, config)
        sdk.get(AnalyticsApi::class.java)
        assertEquals("$name: after get<Ana>", expected.afterAna, sdk.builtProvisionCount)
    }

    // ════════════════════════════════════════════════════════
    // 4. FULL CASCADE — get<Sync> builds everything except Ana
    // ════════════════════════════════════════════════════════

    @Test fun getSync_D() = assertGetSync("D")
    @Test fun getSync_E2() = assertGetSync("E2")
    @Test fun getSync_G() = assertGetSync("G")
    @Test fun getSync_H() = assertGetSync("H")
    @Test fun getSync_I() = assertGetSync("I")
    @Test fun getSync_J() = assertGetSync("J")
    @Test fun getSync_K() = assertGetSync("K")
    @Test fun getSync_L() = assertGetSync("L")
    @Test fun getSync_M() = assertGetSync("M")
    @Test fun getSync_N() = assertGetSync("N")
    @Test fun getSync_O() = assertGetSync("O")
    @Test fun getSync_P() = assertGetSync("P")
    @Test fun getSync_Q() = assertGetSync("Q")
    @Test fun getSync_O2() = assertGetSync("O2")
    @Test fun getSync_P2() = assertGetSync("P2")
    @Test fun getSync_Q2() = assertGetSync("Q2")

    private fun assertGetSync(name: String) {
        val sdk = sdkByName(name)
        val expected = EXPECTED_COUNTS[name]!!
        sdk.init(testContext, config)
        sdk.get(SyncApi::class.java)
        assertEquals("$name: after get<Sync>", expected.afterSync, sdk.builtProvisionCount)
    }

    // ════════════════════════════════════════════════════════
    // 5. FULL GRAPH — all services requested
    // ════════════════════════════════════════════════════════

    @Test fun fullGraph_D() = assertFullGraph("D")
    @Test fun fullGraph_E2() = assertFullGraph("E2")
    @Test fun fullGraph_G() = assertFullGraph("G")
    @Test fun fullGraph_H() = assertFullGraph("H")
    @Test fun fullGraph_I() = assertFullGraph("I")
    @Test fun fullGraph_J() = assertFullGraph("J")
    @Test fun fullGraph_K() = assertFullGraph("K")
    @Test fun fullGraph_L() = assertFullGraph("L")
    @Test fun fullGraph_M() = assertFullGraph("M")
    @Test fun fullGraph_N() = assertFullGraph("N")
    @Test fun fullGraph_O() = assertFullGraph("O")
    @Test fun fullGraph_P() = assertFullGraph("P")
    @Test fun fullGraph_Q() = assertFullGraph("Q")
    @Test fun fullGraph_O2() = assertFullGraph("O2")
    @Test fun fullGraph_P2() = assertFullGraph("P2")
    @Test fun fullGraph_Q2() = assertFullGraph("Q2")

    private fun assertFullGraph(name: String) {
        val sdk = sdkByName(name)
        val expected = EXPECTED_COUNTS[name]!!
        sdk.init(testContext, config)
        sdk.get(SyncApi::class.java)
        sdk.get(AnalyticsApi::class.java)
        assertEquals("$name: full graph", expected.fullGraph, sdk.builtProvisionCount)
    }

    // ════════════════════════════════════════════════════════
    // 6. CLEAN TEARDOWN — shutdown releases all
    // ════════════════════════════════════════════════════════

    @Test fun shutdown_D() = assertShutdown("D")
    @Test fun shutdown_E2() = assertShutdown("E2")
    @Test fun shutdown_G() = assertShutdown("G")
    @Test fun shutdown_H() = assertShutdown("H")
    @Test fun shutdown_I() = assertShutdown("I")
    @Test fun shutdown_J() = assertShutdown("J")
    @Test fun shutdown_K() = assertShutdown("K")
    @Test fun shutdown_L() = assertShutdown("L")
    @Test fun shutdown_M() = assertShutdown("M")
    @Test fun shutdown_N() = assertShutdown("N")
    @Test fun shutdown_O() = assertShutdown("O")
    @Test fun shutdown_P() = assertShutdown("P")
    @Test fun shutdown_Q() = assertShutdown("Q")
    @Test fun shutdown_O2() = assertShutdown("O2")
    @Test fun shutdown_P2() = assertShutdown("P2")
    @Test fun shutdown_Q2() = assertShutdown("Q2")

    private fun assertShutdown(name: String) {
        val sdk = sdkByName(name)
        sdk.init(testContext, config)
        sdk.get(SyncApi::class.java)
        sdk.shutdown()
        assertEquals("$name: after shutdown", 0, sdk.builtProvisionCount)
    }

    // ════════════════════════════════════════════════════════
    // 7. FRESH INSTANCES — reinit produces new objects
    // ════════════════════════════════════════════════════════

    @Test fun freshInstances_D() = assertFreshInstances("D")
    @Test fun freshInstances_E2() = assertFreshInstances("E2")
    @Test fun freshInstances_G() = assertFreshInstances("G")
    @Test fun freshInstances_H() = assertFreshInstances("H")
    @Test fun freshInstances_I() = assertFreshInstances("I")
    @Test fun freshInstances_J() = assertFreshInstances("J")
    @Test fun freshInstances_K() = assertFreshInstances("K")
    @Test fun freshInstances_L() = assertFreshInstances("L")
    @Test fun freshInstances_M() = assertFreshInstances("M")
    @Test fun freshInstances_N() = assertFreshInstances("N")
    @Test fun freshInstances_O() = assertFreshInstances("O")
    @Test fun freshInstances_P() = assertFreshInstances("P")
    @Test fun freshInstances_Q() = assertFreshInstances("Q")
    @Test fun freshInstances_O2() = assertFreshInstances("O2")
    @Test fun freshInstances_P2() = assertFreshInstances("P2")
    @Test fun freshInstances_Q2() = assertFreshInstances("Q2")

    private fun assertFreshInstances(name: String) {
        val sdk = sdkByName(name)
        sdk.init(testContext, config)
        val enc1 = sdk.get(EncryptionApi::class.java)
        sdk.shutdown()
        sdk.init(testContext, config)
        val enc2 = sdk.get(EncryptionApi::class.java)
        sdk.shutdown()
        assertNotSame("$name: reinit must produce fresh instances", enc1, enc2)
    }

    // ════════════════════════════════════════════════════════
    // 8. LEAK DETECTION — 1000 cycles
    // ════════════════════════════════════════════════════════

    @Test fun leakDetection_D() = assertNoLeak("D")
    @Test fun leakDetection_E2() = assertNoLeak("E2")
    @Test fun leakDetection_G() = assertNoLeak("G")
    @Test fun leakDetection_H() = assertNoLeak("H")
    @Test fun leakDetection_I() = assertNoLeak("I")
    @Test fun leakDetection_J() = assertNoLeak("J")
    @Test fun leakDetection_K() = assertNoLeak("K")
    @Test fun leakDetection_L() = assertNoLeak("L")
    @Test fun leakDetection_M() = assertNoLeak("M")
    @Test fun leakDetection_N() = assertNoLeak("N")
    @Test fun leakDetection_O() = assertNoLeak("O")
    @Test fun leakDetection_P() = assertNoLeak("P")
    @Test fun leakDetection_Q() = assertNoLeak("Q")
    @Test fun leakDetection_O2() = assertNoLeak("O2")
    @Test fun leakDetection_P2() = assertNoLeak("P2")
    @Test fun leakDetection_Q2() = assertNoLeak("Q2")

    private fun assertNoLeak(name: String) {
        val sdk = sdkByName(name)
        val expected = EXPECTED_COUNTS[name]!!
        // Warmup
        repeat(10) { sdk.init(testContext, config); sdk.get(SyncApi::class.java); sdk.shutdown() }
        forceGc()
        val heapBefore = usedHeapKb()
        repeat(1000) {
            sdk.init(testContext, config)
            sdk.get(SyncApi::class.java)
            sdk.get(AnalyticsApi::class.java)
            assertEquals(expected.fullGraph, sdk.builtProvisionCount)
            sdk.shutdown()
            assertEquals(0, sdk.builtProvisionCount)
        }
        forceGc()
        val heapAfter = usedHeapKb()
        val delta = heapAfter - heapBefore
        Log.d("LEAK_$name", "1000 cycles: heap delta = $delta KB")
        assertTrue("$name: potential leak ($delta KB)", delta < 2048)
    }

    // ════════════════════════════════════════════════════════
    // 9. HEAP FOOTPRINT — comparative across all patterns
    // ════════════════════════════════════════════════════════

    @Test
    fun heapFootprint_comparative() {
        val tag = "HEAP_COMPARE"
        val results = mutableListOf<String>()

        for ((name, sdk) in ALL_LAZY_SDKS) {
            forceGc()
            val before = usedHeapKb()
            sdk.init(testContext, config)
            forceGc()
            val afterInit = usedHeapKb()
            sdk.get(SyncApi::class.java)
            sdk.get(AnalyticsApi::class.java)
            forceGc()
            val afterFull = usedHeapKb()
            results.add("$name | init: +${afterInit - before} KB (${sdk.builtProvisionCount} prov) | full: +${afterFull - before} KB")
            sdk.shutdown()
        }

        Log.d(tag, "╔════════════════════════════════════════════════╗")
        Log.d(tag, "║    HEAP FOOTPRINT — ALL LAZY PATTERNS          ║")
        Log.d(tag, "╠════════════════════════════════════════════════╣")
        for (line in results) Log.d(tag, "║ $line")
        Log.d(tag, "╚════════════════════════════════════════════════╝")
    }

    // ════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════

    private fun sdkByName(name: String): MultiModuleSdkApi =
        ALL_LAZY_SDKS.first { it.first == name }.second

    /**
     * Fuerza la ejecucion del Garbage Collector antes de medir memoria.
     *
     * System.gc() es una SUGERENCIA — la JVM/ART puede ignorarla.
     * Por eso llamamos multiples veces con yield() y sleep() entre medio:
     *
     * 1. System.gc()     — sugiere al GC que recoja objetos sin referencia
     * 2. Thread.yield()  — cede la CPU al thread del GC para que pueda arrancar
     *                      (sin yield, nuestro thread podria seguir ejecutando
     *                       y el GC thread no obtener tiempo de CPU)
     * 3. System.gc()     — segunda sugerencia por si la primera no se ejecuto
     * 4. Thread.sleep(50) — pausa 50ms para dar tiempo al GC a completar
     *                       (el GC puede necesitar varios ms para recorrer el heap)
     *
     * No es una garantia de que el GC corra, pero en la practica funciona
     * consistentemente en Android/ART con estas multiples sugerencias.
     */
    private fun forceGc() {
        System.gc(); Thread.yield(); System.gc(); Thread.sleep(50)
    }

    /**
     * Mide la memoria heap usada por objetos Java/Kotlin vivos.
     *
     * HEAP = zona de memoria donde viven todos los objetos creados con constructores.
     * Cuando haces `val enc = DefaultEncryptionService(logger)`, esa instancia
     * ocupa espacio en el heap hasta que el GC la recoge.
     *
     * - totalMemory() = heap total asignado al proceso por la JVM/ART
     * - freeMemory()  = parte del heap que esta libre (no tiene objetos)
     * - La resta       = memoria ocupada por objetos vivos
     * - Dividido por 1024 = resultado en kilobytes (KB)
     *
     * Usado en leakDetection: si tras 1,000 ciclos init/shutdown el heap
     * crece mas de 2,048 KB, hay objetos que no se liberan (memory leak).
     */
    private fun usedHeapKb(): Long {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / 1024
    }
}

package com.grinwich.benchmark

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grinwich.sdk.api.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Memory behavior tests — proves lazy instantiation across all 6 lazy patterns.
 *
 * Deterministic assertion tests (not benchmarks). Each test category is written
 * ONCE and executed for all patterns via [MultiModuleSdkApi].
 */
@RunWith(AndroidJUnit4::class)
class MemoryBehaviorTest {

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

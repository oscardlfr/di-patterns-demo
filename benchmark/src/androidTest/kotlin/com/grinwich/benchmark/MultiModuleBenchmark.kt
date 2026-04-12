package com.grinwich.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grinwich.sdk.api.*
import com.grinwich.sdk.api.StorageBackend
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Microbenchmarks comparing 16 lazy multi-module wiring patterns.
 *
 * All patterns implement [MultiModuleSdkApi] — same consumer surface:
 * init(config) → get<T>() → shutdown()
 *
 * Patterns: D (when-block), E2 (AutoRegistry DFS), G (factory),
 *           H (Dagger+ServiceLoader), I (Pure Resolver), J (kotlin-inject),
 *           K (AndroidManifest), L, M, N, O, P, Q
 *
 * Run: ./gradlew :benchmark:connectedReleaseAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class MultiModuleBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    @get:Rule
    val patternFilter = PatternFilterRule()

    private val config = SdkConfig(debug = false)

    @After
    fun tearDown() {
        ALL_LAZY_SDKS.forEach { (_, sdk) -> sdk.shutdown() }
    }

    // ════════════════════════════════════════════════════════
    // 1. INIT COLD — full graph from scratch
    // ════════════════════════════════════════════════════════

    @Test fun initCold_D() = initCold(ALL_LAZY_SDKS[0].second)
    @Test fun initCold_E2() = initCold(ALL_LAZY_SDKS[1].second)
    @Test fun initCold_G() = initCold(ALL_LAZY_SDKS[2].second)
    @Test fun initCold_H() = initCold(ALL_LAZY_SDKS[3].second)
    @Test fun initCold_I() = initCold(ALL_LAZY_SDKS[4].second)
    @Test fun initCold_J() = initCold(ALL_LAZY_SDKS[5].second)
    @Test fun initCold_K() = initCold(ALL_LAZY_SDKS[6].second)
    @Test fun initCold_L() = initCold(ALL_LAZY_SDKS[7].second)
    @Test fun initCold_M() = initCold(ALL_LAZY_SDKS[8].second)
    @Test fun initCold_N() = initCold(ALL_LAZY_SDKS[9].second)
    @Test fun initCold_O() = initCold(ALL_LAZY_SDKS[10].second)
    @Test fun initCold_P() = initCold(ALL_LAZY_SDKS[11].second)
    @Test fun initCold_Q() = initCold(ALL_LAZY_SDKS[12].second)
    @Test fun initCold_O2() = initCold(ALL_LAZY_SDKS[13].second)
    @Test fun initCold_P2() = initCold(ALL_LAZY_SDKS[14].second)
    @Test fun initCold_Q2() = initCold(ALL_LAZY_SDKS[15].second)

    private fun initCold(sdk: MultiModuleSdkApi) = benchmarkRule.measureRepeated {
        sdk.init(testContext, config)
        sdk.get(EncryptionApi::class.java)
        sdk.get(HashApi::class.java)
        sdk.get(AuthApi::class.java)
        sdk.get(StorageApi::class.java)
        sdk.get(AnalyticsApi::class.java)
        sdk.get(SyncApi::class.java)
        runWithMeasurementDisabled { sdk.shutdown() }
    }

    // ════════════════════════════════════════════════════════
    // 2. RESOLVE FIRST — cached resolution from built graph
    // ════════════════════════════════════════════════════════

    @Test fun resolveFirst_D() = resolveFirst(ALL_LAZY_SDKS[0].second)
    @Test fun resolveFirst_E2() = resolveFirst(ALL_LAZY_SDKS[1].second)
    @Test fun resolveFirst_G() = resolveFirst(ALL_LAZY_SDKS[2].second)
    @Test fun resolveFirst_H() = resolveFirst(ALL_LAZY_SDKS[3].second)
    @Test fun resolveFirst_I() = resolveFirst(ALL_LAZY_SDKS[4].second)
    @Test fun resolveFirst_J() = resolveFirst(ALL_LAZY_SDKS[5].second)
    @Test fun resolveFirst_K() = resolveFirst(ALL_LAZY_SDKS[6].second)
    @Test fun resolveFirst_L() = resolveFirst(ALL_LAZY_SDKS[7].second)
    @Test fun resolveFirst_M() = resolveFirst(ALL_LAZY_SDKS[8].second)
    @Test fun resolveFirst_N() = resolveFirst(ALL_LAZY_SDKS[9].second)
    @Test fun resolveFirst_O() = resolveFirst(ALL_LAZY_SDKS[10].second)
    @Test fun resolveFirst_P() = resolveFirst(ALL_LAZY_SDKS[11].second)
    @Test fun resolveFirst_Q() = resolveFirst(ALL_LAZY_SDKS[12].second)
    @Test fun resolveFirst_O2() = resolveFirst(ALL_LAZY_SDKS[13].second)
    @Test fun resolveFirst_P2() = resolveFirst(ALL_LAZY_SDKS[14].second)
    @Test fun resolveFirst_Q2() = resolveFirst(ALL_LAZY_SDKS[15].second)

    private fun resolveFirst(sdk: MultiModuleSdkApi) {
        sdk.init(testContext, config)
        // Warm: first get triggers build
        sdk.get(EncryptionApi::class.java)
        benchmarkRule.measureRepeated {
            sdk.get(EncryptionApi::class.java)
        }
        sdk.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // 3. LAZY INIT — standalone feature (Analytics, zero cross-deps)
    // ════════════════════════════════════════════════════════

    @Test fun lazyInit_noDeps_D() = lazyInitNoDeps(ALL_LAZY_SDKS[0].second)
    @Test fun lazyInit_noDeps_E2() = lazyInitNoDeps(ALL_LAZY_SDKS[1].second)
    @Test fun lazyInit_noDeps_G() = lazyInitNoDeps(ALL_LAZY_SDKS[2].second)
    @Test fun lazyInit_noDeps_H() = lazyInitNoDeps(ALL_LAZY_SDKS[3].second)
    @Test fun lazyInit_noDeps_I() = lazyInitNoDeps(ALL_LAZY_SDKS[4].second)
    @Test fun lazyInit_noDeps_J() = lazyInitNoDeps(ALL_LAZY_SDKS[5].second)
    @Test fun lazyInit_noDeps_K() = lazyInitNoDeps(ALL_LAZY_SDKS[6].second)
    @Test fun lazyInit_noDeps_L() = lazyInitNoDeps(ALL_LAZY_SDKS[7].second)
    @Test fun lazyInit_noDeps_M() = lazyInitNoDeps(ALL_LAZY_SDKS[8].second)
    @Test fun lazyInit_noDeps_N() = lazyInitNoDeps(ALL_LAZY_SDKS[9].second)
    @Test fun lazyInit_noDeps_O() = lazyInitNoDeps(ALL_LAZY_SDKS[10].second)
    @Test fun lazyInit_noDeps_P() = lazyInitNoDeps(ALL_LAZY_SDKS[11].second)
    @Test fun lazyInit_noDeps_Q() = lazyInitNoDeps(ALL_LAZY_SDKS[12].second)
    @Test fun lazyInit_noDeps_O2() = lazyInitNoDeps(ALL_LAZY_SDKS[13].second)
    @Test fun lazyInit_noDeps_P2() = lazyInitNoDeps(ALL_LAZY_SDKS[14].second)
    @Test fun lazyInit_noDeps_Q2() = lazyInitNoDeps(ALL_LAZY_SDKS[15].second)

    private fun lazyInitNoDeps(sdk: MultiModuleSdkApi) = benchmarkRule.measureRepeated {
        runWithMeasurementDisabled {
            sdk.init(testContext, config)
            sdk.get(EncryptionApi::class.java)  // warm base graph
        }
        sdk.get(AnalyticsApi::class.java)  // measure adding standalone feature
        runWithMeasurementDisabled { sdk.shutdown() }
    }

    // ════════════════════════════════════════════════════════
    // 4. LAZY INIT — cascade (Sync → Auth + Stor + Enc)
    // ════════════════════════════════════════════════════════

    @Test fun lazyInit_cascade_D() = lazyInitCascade(ALL_LAZY_SDKS[0].second)
    @Test fun lazyInit_cascade_E2() = lazyInitCascade(ALL_LAZY_SDKS[1].second)
    @Test fun lazyInit_cascade_G() = lazyInitCascade(ALL_LAZY_SDKS[2].second)
    @Test fun lazyInit_cascade_H() = lazyInitCascade(ALL_LAZY_SDKS[3].second)
    @Test fun lazyInit_cascade_I() = lazyInitCascade(ALL_LAZY_SDKS[4].second)
    @Test fun lazyInit_cascade_J() = lazyInitCascade(ALL_LAZY_SDKS[5].second)
    @Test fun lazyInit_cascade_K() = lazyInitCascade(ALL_LAZY_SDKS[6].second)
    @Test fun lazyInit_cascade_L() = lazyInitCascade(ALL_LAZY_SDKS[7].second)
    @Test fun lazyInit_cascade_M() = lazyInitCascade(ALL_LAZY_SDKS[8].second)
    @Test fun lazyInit_cascade_N() = lazyInitCascade(ALL_LAZY_SDKS[9].second)
    @Test fun lazyInit_cascade_O() = lazyInitCascade(ALL_LAZY_SDKS[10].second)
    @Test fun lazyInit_cascade_P() = lazyInitCascade(ALL_LAZY_SDKS[11].second)
    @Test fun lazyInit_cascade_Q() = lazyInitCascade(ALL_LAZY_SDKS[12].second)
    @Test fun lazyInit_cascade_O2() = lazyInitCascade(ALL_LAZY_SDKS[13].second)
    @Test fun lazyInit_cascade_P2() = lazyInitCascade(ALL_LAZY_SDKS[14].second)
    @Test fun lazyInit_cascade_Q2() = lazyInitCascade(ALL_LAZY_SDKS[15].second)

    private fun lazyInitCascade(sdk: MultiModuleSdkApi) = benchmarkRule.measureRepeated {
        runWithMeasurementDisabled {
            sdk.init(testContext, config)
            sdk.get(EncryptionApi::class.java)  // warm Enc
        }
        sdk.get(SyncApi::class.java)  // cascades: Auth + Stor + Syn
        runWithMeasurementDisabled { sdk.shutdown() }
    }

    // ════════════════════════════════════════════════════════
    // 5. CROSS-FEATURE OP — real work crossing Auth+Storage+Encryption
    // ════════════════════════════════════════════════════════

    @Test fun crossFeatureOp_D() = crossFeatureOp(ALL_LAZY_SDKS[0].second)
    @Test fun crossFeatureOp_E2() = crossFeatureOp(ALL_LAZY_SDKS[1].second)
    @Test fun crossFeatureOp_G() = crossFeatureOp(ALL_LAZY_SDKS[2].second)
    @Test fun crossFeatureOp_H() = crossFeatureOp(ALL_LAZY_SDKS[3].second)
    @Test fun crossFeatureOp_I() = crossFeatureOp(ALL_LAZY_SDKS[4].second)
    @Test fun crossFeatureOp_J() = crossFeatureOp(ALL_LAZY_SDKS[5].second)
    @Test fun crossFeatureOp_K() = crossFeatureOp(ALL_LAZY_SDKS[6].second)
    @Test fun crossFeatureOp_L() = crossFeatureOp(ALL_LAZY_SDKS[7].second)
    @Test fun crossFeatureOp_M() = crossFeatureOp(ALL_LAZY_SDKS[8].second)
    @Test fun crossFeatureOp_N() = crossFeatureOp(ALL_LAZY_SDKS[9].second)
    @Test fun crossFeatureOp_O() = crossFeatureOp(ALL_LAZY_SDKS[10].second)
    @Test fun crossFeatureOp_P() = crossFeatureOp(ALL_LAZY_SDKS[11].second)
    @Test fun crossFeatureOp_Q() = crossFeatureOp(ALL_LAZY_SDKS[12].second)
    @Test fun crossFeatureOp_O2() = crossFeatureOp(ALL_LAZY_SDKS[13].second)
    @Test fun crossFeatureOp_P2() = crossFeatureOp(ALL_LAZY_SDKS[14].second)
    @Test fun crossFeatureOp_Q2() = crossFeatureOp(ALL_LAZY_SDKS[15].second)

    private fun crossFeatureOp(sdk: MultiModuleSdkApi) {
        sdk.init(testContext, config)
        sdk.get(AuthApi::class.java).login("bench", "pass")
        val sync = sdk.get(SyncApi::class.java)
        benchmarkRule.measureRepeated {
            runBlocking { sync.sync() }
        }
        sdk.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // 6. STRESS — Init/Shutdown cycle
    // ════════════════════════════════════════════════════════

    @Test fun stress_initShutdown_D() = stressInitShutdown(ALL_LAZY_SDKS[0].second)
    @Test fun stress_initShutdown_E2() = stressInitShutdown(ALL_LAZY_SDKS[1].second)
    @Test fun stress_initShutdown_G() = stressInitShutdown(ALL_LAZY_SDKS[2].second)
    @Test fun stress_initShutdown_H() = stressInitShutdown(ALL_LAZY_SDKS[3].second)
    @Test fun stress_initShutdown_I() = stressInitShutdown(ALL_LAZY_SDKS[4].second)
    @Test fun stress_initShutdown_J() = stressInitShutdown(ALL_LAZY_SDKS[5].second)
    @Test fun stress_initShutdown_K() = stressInitShutdown(ALL_LAZY_SDKS[6].second)
    @Test fun stress_initShutdown_L() = stressInitShutdown(ALL_LAZY_SDKS[7].second)
    @Test fun stress_initShutdown_M() = stressInitShutdown(ALL_LAZY_SDKS[8].second)
    @Test fun stress_initShutdown_N() = stressInitShutdown(ALL_LAZY_SDKS[9].second)
    @Test fun stress_initShutdown_O() = stressInitShutdown(ALL_LAZY_SDKS[10].second)
    @Test fun stress_initShutdown_P() = stressInitShutdown(ALL_LAZY_SDKS[11].second)
    @Test fun stress_initShutdown_Q() = stressInitShutdown(ALL_LAZY_SDKS[12].second)
    @Test fun stress_initShutdown_O2() = stressInitShutdown(ALL_LAZY_SDKS[13].second)
    @Test fun stress_initShutdown_P2() = stressInitShutdown(ALL_LAZY_SDKS[14].second)
    @Test fun stress_initShutdown_Q2() = stressInitShutdown(ALL_LAZY_SDKS[15].second)

    private fun stressInitShutdown(sdk: MultiModuleSdkApi) = benchmarkRule.measureRepeated {
        sdk.init(testContext, config)
        sdk.get(EncryptionApi::class.java)
        sdk.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // 7. STRESS — Concurrent resolve (4 threads)
    // ════════════════════════════════════════════════════════

    @Test fun stress_concurrent_D() = stressConcurrent(ALL_LAZY_SDKS[0].second)
    @Test fun stress_concurrent_E2() = stressConcurrent(ALL_LAZY_SDKS[1].second)
    @Test fun stress_concurrent_G() = stressConcurrent(ALL_LAZY_SDKS[2].second)
    @Test fun stress_concurrent_H() = stressConcurrent(ALL_LAZY_SDKS[3].second)
    @Test fun stress_concurrent_I() = stressConcurrent(ALL_LAZY_SDKS[4].second)
    @Test fun stress_concurrent_J() = stressConcurrent(ALL_LAZY_SDKS[5].second)
    @Test fun stress_concurrent_K() = stressConcurrent(ALL_LAZY_SDKS[6].second)
    @Test fun stress_concurrent_L() = stressConcurrent(ALL_LAZY_SDKS[7].second)
    @Test fun stress_concurrent_M() = stressConcurrent(ALL_LAZY_SDKS[8].second)
    @Test fun stress_concurrent_N() = stressConcurrent(ALL_LAZY_SDKS[9].second)
    @Test fun stress_concurrent_O() = stressConcurrent(ALL_LAZY_SDKS[10].second)
    @Test fun stress_concurrent_P() = stressConcurrent(ALL_LAZY_SDKS[11].second)
    @Test fun stress_concurrent_Q() = stressConcurrent(ALL_LAZY_SDKS[12].second)
    @Test fun stress_concurrent_O2() = stressConcurrent(ALL_LAZY_SDKS[13].second)
    @Test fun stress_concurrent_P2() = stressConcurrent(ALL_LAZY_SDKS[14].second)
    @Test fun stress_concurrent_Q2() = stressConcurrent(ALL_LAZY_SDKS[15].second)

    private fun stressConcurrent(sdk: MultiModuleSdkApi) {
        sdk.init(testContext, config)
        sdk.get(SyncApi::class.java)
        sdk.get(AnalyticsApi::class.java)
        benchmarkRule.measureRepeated {
            val threads = listOf(
                Thread { sdk.get(EncryptionApi::class.java) },
                Thread { sdk.get(AuthApi::class.java) },
                Thread { sdk.get(StorageApi::class.java) },
                Thread { sdk.get(SyncApi::class.java) },
            )
            threads.forEach { it.start() }
            threads.forEach { it.join() }
        }
        sdk.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // 8. STRESS — Resolve all sequential (cached)
    // ════════════════════════════════════════════════════════

    @Test fun stress_resolveAll_D() = stressResolveAll(ALL_LAZY_SDKS[0].second)
    @Test fun stress_resolveAll_E2() = stressResolveAll(ALL_LAZY_SDKS[1].second)
    @Test fun stress_resolveAll_G() = stressResolveAll(ALL_LAZY_SDKS[2].second)
    @Test fun stress_resolveAll_H() = stressResolveAll(ALL_LAZY_SDKS[3].second)
    @Test fun stress_resolveAll_I() = stressResolveAll(ALL_LAZY_SDKS[4].second)
    @Test fun stress_resolveAll_J() = stressResolveAll(ALL_LAZY_SDKS[5].second)
    @Test fun stress_resolveAll_K() = stressResolveAll(ALL_LAZY_SDKS[6].second)
    @Test fun stress_resolveAll_L() = stressResolveAll(ALL_LAZY_SDKS[7].second)
    @Test fun stress_resolveAll_M() = stressResolveAll(ALL_LAZY_SDKS[8].second)
    @Test fun stress_resolveAll_N() = stressResolveAll(ALL_LAZY_SDKS[9].second)
    @Test fun stress_resolveAll_O() = stressResolveAll(ALL_LAZY_SDKS[10].second)
    @Test fun stress_resolveAll_P() = stressResolveAll(ALL_LAZY_SDKS[11].second)
    @Test fun stress_resolveAll_Q() = stressResolveAll(ALL_LAZY_SDKS[12].second)
    @Test fun stress_resolveAll_O2() = stressResolveAll(ALL_LAZY_SDKS[13].second)
    @Test fun stress_resolveAll_P2() = stressResolveAll(ALL_LAZY_SDKS[14].second)
    @Test fun stress_resolveAll_Q2() = stressResolveAll(ALL_LAZY_SDKS[15].second)

    private fun stressResolveAll(sdk: MultiModuleSdkApi) {
        sdk.init(testContext, config)
        sdk.get(SyncApi::class.java)
        sdk.get(AnalyticsApi::class.java)
        benchmarkRule.measureRepeated {
            sdk.get(EncryptionApi::class.java)
            sdk.get(HashApi::class.java)
            sdk.get(AuthApi::class.java)
            sdk.get(StorageApi::class.java)
            sdk.get(AnalyticsApi::class.java)
            sdk.get(SyncApi::class.java)
        }
        sdk.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // 9. STRESS — Selective init (only Analytics)
    // ════════════════════════════════════════════════════════

    @Test fun stress_selective_D() = stressSelective(ALL_LAZY_SDKS[0].second)
    @Test fun stress_selective_E2() = stressSelective(ALL_LAZY_SDKS[1].second)
    @Test fun stress_selective_G() = stressSelective(ALL_LAZY_SDKS[2].second)
    @Test fun stress_selective_H() = stressSelective(ALL_LAZY_SDKS[3].second)
    @Test fun stress_selective_I() = stressSelective(ALL_LAZY_SDKS[4].second)
    @Test fun stress_selective_J() = stressSelective(ALL_LAZY_SDKS[5].second)
    @Test fun stress_selective_K() = stressSelective(ALL_LAZY_SDKS[6].second)
    @Test fun stress_selective_L() = stressSelective(ALL_LAZY_SDKS[7].second)
    @Test fun stress_selective_M() = stressSelective(ALL_LAZY_SDKS[8].second)
    @Test fun stress_selective_N() = stressSelective(ALL_LAZY_SDKS[9].second)
    @Test fun stress_selective_O() = stressSelective(ALL_LAZY_SDKS[10].second)
    @Test fun stress_selective_P() = stressSelective(ALL_LAZY_SDKS[11].second)
    @Test fun stress_selective_Q() = stressSelective(ALL_LAZY_SDKS[12].second)
    @Test fun stress_selective_O2() = stressSelective(ALL_LAZY_SDKS[13].second)
    @Test fun stress_selective_P2() = stressSelective(ALL_LAZY_SDKS[14].second)
    @Test fun stress_selective_Q2() = stressSelective(ALL_LAZY_SDKS[15].second)

    private fun stressSelective(sdk: MultiModuleSdkApi) = benchmarkRule.measureRepeated {
        sdk.init(testContext, config)
        sdk.get(AnalyticsApi::class.java)  // only Analytics + deps
        runWithMeasurementDisabled { sdk.shutdown() }
    }

    // ════════════════════════════════════════════════════════
    // 10. STRESS — Re-init after shutdown (two full cycles)
    // ════════════════════════════════════════════════════════

    @Test fun stress_reInit_D() = stressReInit(ALL_LAZY_SDKS[0].second)
    @Test fun stress_reInit_E2() = stressReInit(ALL_LAZY_SDKS[1].second)
    @Test fun stress_reInit_G() = stressReInit(ALL_LAZY_SDKS[2].second)
    @Test fun stress_reInit_H() = stressReInit(ALL_LAZY_SDKS[3].second)
    @Test fun stress_reInit_I() = stressReInit(ALL_LAZY_SDKS[4].second)
    @Test fun stress_reInit_J() = stressReInit(ALL_LAZY_SDKS[5].second)
    @Test fun stress_reInit_K() = stressReInit(ALL_LAZY_SDKS[6].second)
    @Test fun stress_reInit_L() = stressReInit(ALL_LAZY_SDKS[7].second)
    @Test fun stress_reInit_M() = stressReInit(ALL_LAZY_SDKS[8].second)
    @Test fun stress_reInit_N() = stressReInit(ALL_LAZY_SDKS[9].second)
    @Test fun stress_reInit_O() = stressReInit(ALL_LAZY_SDKS[10].second)
    @Test fun stress_reInit_P() = stressReInit(ALL_LAZY_SDKS[11].second)
    @Test fun stress_reInit_Q() = stressReInit(ALL_LAZY_SDKS[12].second)
    @Test fun stress_reInit_O2() = stressReInit(ALL_LAZY_SDKS[13].second)
    @Test fun stress_reInit_P2() = stressReInit(ALL_LAZY_SDKS[14].second)
    @Test fun stress_reInit_Q2() = stressReInit(ALL_LAZY_SDKS[15].second)

    private fun stressReInit(sdk: MultiModuleSdkApi) = benchmarkRule.measureRepeated {
        sdk.init(testContext, config)
        sdk.get(SyncApi::class.java)
        sdk.get(AnalyticsApi::class.java)
        sdk.shutdown()
        sdk.init(testContext, config)
        sdk.get(SyncApi::class.java)
        sdk.get(AnalyticsApi::class.java)
        sdk.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // 11. STRESS — Incremental build (add features one by one)
    // ════════════════════════════════════════════════════════

    @Test fun stress_incremental_D() = stressIncremental(ALL_LAZY_SDKS[0].second)
    @Test fun stress_incremental_E2() = stressIncremental(ALL_LAZY_SDKS[1].second)
    @Test fun stress_incremental_G() = stressIncremental(ALL_LAZY_SDKS[2].second)
    @Test fun stress_incremental_H() = stressIncremental(ALL_LAZY_SDKS[3].second)
    @Test fun stress_incremental_I() = stressIncremental(ALL_LAZY_SDKS[4].second)
    @Test fun stress_incremental_J() = stressIncremental(ALL_LAZY_SDKS[5].second)
    @Test fun stress_incremental_K() = stressIncremental(ALL_LAZY_SDKS[6].second)
    @Test fun stress_incremental_L() = stressIncremental(ALL_LAZY_SDKS[7].second)
    @Test fun stress_incremental_M() = stressIncremental(ALL_LAZY_SDKS[8].second)
    @Test fun stress_incremental_N() = stressIncremental(ALL_LAZY_SDKS[9].second)
    @Test fun stress_incremental_O() = stressIncremental(ALL_LAZY_SDKS[10].second)
    @Test fun stress_incremental_P() = stressIncremental(ALL_LAZY_SDKS[11].second)
    @Test fun stress_incremental_Q() = stressIncremental(ALL_LAZY_SDKS[12].second)
    @Test fun stress_incremental_O2() = stressIncremental(ALL_LAZY_SDKS[13].second)
    @Test fun stress_incremental_P2() = stressIncremental(ALL_LAZY_SDKS[14].second)
    @Test fun stress_incremental_Q2() = stressIncremental(ALL_LAZY_SDKS[15].second)

    private fun stressIncremental(sdk: MultiModuleSdkApi) = benchmarkRule.measureRepeated {
        sdk.init(testContext, config)
        sdk.get(EncryptionApi::class.java)
        sdk.get(AuthApi::class.java)
        sdk.get(StorageApi::class.java)
        sdk.get(AnalyticsApi::class.java)
        sdk.get(SyncApi::class.java)
        sdk.get(HashApi::class.java)
        runWithMeasurementDisabled { sdk.shutdown() }
    }

    // ════════════════════════════════════════════════════════
    // 12. E2E APP STARTUP — simulates real app: init + resolve + use
    //     Measures total cost from SDK init to first meaningful operation.
    //     This is what the consumer app pays on Application.onCreate().
    // ════════════════════════════════════════════════════════

    @Test fun e2eStartup_D() = e2eAppStartup(ALL_LAZY_SDKS[0].second)
    @Test fun e2eStartup_E2() = e2eAppStartup(ALL_LAZY_SDKS[1].second)
    @Test fun e2eStartup_G() = e2eAppStartup(ALL_LAZY_SDKS[2].second)
    @Test fun e2eStartup_H() = e2eAppStartup(ALL_LAZY_SDKS[3].second)
    @Test fun e2eStartup_I() = e2eAppStartup(ALL_LAZY_SDKS[4].second)
    @Test fun e2eStartup_J() = e2eAppStartup(ALL_LAZY_SDKS[5].second)
    @Test fun e2eStartup_K() = e2eAppStartup(ALL_LAZY_SDKS[6].second)
    @Test fun e2eStartup_L() = e2eAppStartup(ALL_LAZY_SDKS[7].second)
    @Test fun e2eStartup_M() = e2eAppStartup(ALL_LAZY_SDKS[8].second)
    @Test fun e2eStartup_N() = e2eAppStartup(ALL_LAZY_SDKS[9].second)
    @Test fun e2eStartup_O() = e2eAppStartup(ALL_LAZY_SDKS[10].second)
    @Test fun e2eStartup_P() = e2eAppStartup(ALL_LAZY_SDKS[11].second)
    @Test fun e2eStartup_Q() = e2eAppStartup(ALL_LAZY_SDKS[12].second)
    @Test fun e2eStartup_O2() = e2eAppStartup(ALL_LAZY_SDKS[13].second)
    @Test fun e2eStartup_P2() = e2eAppStartup(ALL_LAZY_SDKS[14].second)
    @Test fun e2eStartup_Q2() = e2eAppStartup(ALL_LAZY_SDKS[15].second)

    private fun e2eAppStartup(sdk: MultiModuleSdkApi) = benchmarkRule.measureRepeated {
        // Phase 1: SDK init (ServiceLoader / registration / Core build)
        sdk.init(testContext, config)

        // Phase 2: Resolve all services (Dagger bridge would call these)
        val enc = sdk.get(EncryptionApi::class.java)
        val auth = sdk.get(AuthApi::class.java)
        val stor = sdk.get(StorageApi::class.java)
        val ana = sdk.get(AnalyticsApi::class.java)

        // Phase 3: First real operations (what happens in Activity.onCreate)
        auth.login("user", "pass")
        enc.encrypt("sensitive-data")
        runBlocking { stor.put("session", "active") }
        ana.trackEvent("app_launched")

        runWithMeasurementDisabled { sdk.shutdown() }
    }

    // ════════════════════════════════════════════════════════
    // 13. STORAGE BACKEND COMPARISON — Pattern H
    //
    // Fake vs SharedPreferences vs DataStore on multi-module.
    // Pattern H is the primary multi-module target; D and K
    // included for cross-pattern comparison.
    // ════════════════════════════════════════════════════════

    private val configFake = SdkConfig(debug = false, storageBackend = StorageBackend.FAKE)
    private val configSharedPrefs = SdkConfig(debug = false, storageBackend = StorageBackend.SHARED_PREFS)
    private val configDataStore = SdkConfig(debug = false, storageBackend = StorageBackend.DATA_STORE)

    // -- Pattern H --

    @Test fun crossFeatureOp_H_fake() = crossFeatureOpWith(ALL_LAZY_SDKS[3].second, configFake)
    @Test fun crossFeatureOp_H_sharedprefs() = crossFeatureOpWith(ALL_LAZY_SDKS[3].second, configSharedPrefs)
    @Test fun crossFeatureOp_H_datastore() = crossFeatureOpWith(ALL_LAZY_SDKS[3].second, configDataStore)

    // -- Pattern D --

    @Test fun crossFeatureOp_D_fake() = crossFeatureOpWith(ALL_LAZY_SDKS[0].second, configFake)
    @Test fun crossFeatureOp_D_sharedprefs() = crossFeatureOpWith(ALL_LAZY_SDKS[0].second, configSharedPrefs)
    @Test fun crossFeatureOp_D_datastore() = crossFeatureOpWith(ALL_LAZY_SDKS[0].second, configDataStore)

    // -- Pattern K --

    @Test fun crossFeatureOp_K_fake() = crossFeatureOpWith(ALL_LAZY_SDKS[6].second, configFake)
    @Test fun crossFeatureOp_K_sharedprefs() = crossFeatureOpWith(ALL_LAZY_SDKS[6].second, configSharedPrefs)
    @Test fun crossFeatureOp_K_datastore() = crossFeatureOpWith(ALL_LAZY_SDKS[6].second, configDataStore)

    // -- Pattern L --

    @Test fun crossFeatureOp_L_fake() = crossFeatureOpWith(ALL_LAZY_SDKS[7].second, configFake)
    @Test fun crossFeatureOp_L_sharedprefs() = crossFeatureOpWith(ALL_LAZY_SDKS[7].second, configSharedPrefs)
    @Test fun crossFeatureOp_L_datastore() = crossFeatureOpWith(ALL_LAZY_SDKS[7].second, configDataStore)

    // -- Pattern M --

    @Test fun crossFeatureOp_M_fake() = crossFeatureOpWith(ALL_LAZY_SDKS[8].second, configFake)
    @Test fun crossFeatureOp_M_sharedprefs() = crossFeatureOpWith(ALL_LAZY_SDKS[8].second, configSharedPrefs)
    @Test fun crossFeatureOp_M_datastore() = crossFeatureOpWith(ALL_LAZY_SDKS[8].second, configDataStore)

    // -- Pattern N --

    @Test fun crossFeatureOp_N_fake() = crossFeatureOpWith(ALL_LAZY_SDKS[9].second, configFake)
    @Test fun crossFeatureOp_N_sharedprefs() = crossFeatureOpWith(ALL_LAZY_SDKS[9].second, configSharedPrefs)
    @Test fun crossFeatureOp_N_datastore() = crossFeatureOpWith(ALL_LAZY_SDKS[9].second, configDataStore)

    // -- Pattern O --

    @Test fun crossFeatureOp_O_fake() = crossFeatureOpWith(ALL_LAZY_SDKS[10].second, configFake)
    @Test fun crossFeatureOp_O_sharedprefs() = crossFeatureOpWith(ALL_LAZY_SDKS[10].second, configSharedPrefs)
    @Test fun crossFeatureOp_O_datastore() = crossFeatureOpWith(ALL_LAZY_SDKS[10].second, configDataStore)

    // -- Pattern P --

    @Test fun crossFeatureOp_P_fake() = crossFeatureOpWith(ALL_LAZY_SDKS[11].second, configFake)
    @Test fun crossFeatureOp_P_sharedprefs() = crossFeatureOpWith(ALL_LAZY_SDKS[11].second, configSharedPrefs)
    @Test fun crossFeatureOp_P_datastore() = crossFeatureOpWith(ALL_LAZY_SDKS[11].second, configDataStore)

    // -- Pattern Q --

    @Test fun crossFeatureOp_Q_fake() = crossFeatureOpWith(ALL_LAZY_SDKS[12].second, configFake)
    @Test fun crossFeatureOp_Q_sharedprefs() = crossFeatureOpWith(ALL_LAZY_SDKS[12].second, configSharedPrefs)
    @Test fun crossFeatureOp_Q_datastore() = crossFeatureOpWith(ALL_LAZY_SDKS[12].second, configDataStore)

    // -- Pattern O2 --

    @Test fun crossFeatureOp_O2_fake() = crossFeatureOpWith(ALL_LAZY_SDKS[13].second, configFake)
    @Test fun crossFeatureOp_O2_sharedprefs() = crossFeatureOpWith(ALL_LAZY_SDKS[13].second, configSharedPrefs)
    @Test fun crossFeatureOp_O2_datastore() = crossFeatureOpWith(ALL_LAZY_SDKS[13].second, configDataStore)

    // -- Pattern P2 --

    @Test fun crossFeatureOp_P2_fake() = crossFeatureOpWith(ALL_LAZY_SDKS[14].second, configFake)
    @Test fun crossFeatureOp_P2_sharedprefs() = crossFeatureOpWith(ALL_LAZY_SDKS[14].second, configSharedPrefs)
    @Test fun crossFeatureOp_P2_datastore() = crossFeatureOpWith(ALL_LAZY_SDKS[14].second, configDataStore)

    // -- Pattern Q2 --

    @Test fun crossFeatureOp_Q2_fake() = crossFeatureOpWith(ALL_LAZY_SDKS[15].second, configFake)
    @Test fun crossFeatureOp_Q2_sharedprefs() = crossFeatureOpWith(ALL_LAZY_SDKS[15].second, configSharedPrefs)
    @Test fun crossFeatureOp_Q2_datastore() = crossFeatureOpWith(ALL_LAZY_SDKS[15].second, configDataStore)

    private fun crossFeatureOpWith(sdk: MultiModuleSdkApi, cfg: SdkConfig) {
        sdk.init(testContext, cfg)
        sdk.get(AuthApi::class.java).login("bench", "pass")
        val sync = sdk.get(SyncApi::class.java)
        benchmarkRule.measureRepeated { runBlocking { sync.sync() } }
        sdk.shutdown()
    }
}

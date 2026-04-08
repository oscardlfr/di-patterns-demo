package com.grinwich.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grinwich.sdk.api.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Microbenchmarks comparing 6 lazy multi-module wiring patterns.
 *
 * All patterns implement [MultiModuleSdkApi] — same consumer surface:
 * init(config) → get<T>() → shutdown()
 *
 * Patterns: D (when-block), E2 (AutoRegistry DFS), G (factory),
 *           H (Dagger+ServiceLoader), I (Pure Resolver), J (kotlin-inject)
 *
 * Run: ./gradlew :benchmark:connectedReleaseAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class MultiModuleBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

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

    private fun crossFeatureOp(sdk: MultiModuleSdkApi) {
        sdk.init(testContext, config)
        sdk.get(AuthApi::class.java).login("bench", "pass")
        val sync = sdk.get(SyncApi::class.java)
        benchmarkRule.measureRepeated {
            sync.sync()
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
        stor.put("session", "active")
        ana.trackEvent("app_launched")

        runWithMeasurementDisabled { sdk.shutdown() }
    }
}

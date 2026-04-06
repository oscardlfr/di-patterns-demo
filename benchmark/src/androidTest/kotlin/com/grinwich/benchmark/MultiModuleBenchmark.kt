package com.grinwich.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grinwich.sdk.api.*
import com.grinwich.sdk.wiring.MultiModuleSdk
import com.grinwich.sdk.wiring.e.MultiModuleSdkE
import com.grinwich.sdk.wiring.e2.MultiModuleSdkE2
import com.grinwich.sdk.wiring.g.MultiModuleSdkG
import com.grinwich.sdk.wiring.h.MultiModuleSdkH
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Microbenchmarks comparing multi-module wiring patterns.
 *
 * All three use the SAME feature-impl modules (same Dagger components),
 * only the wiring strategy differs:
 * - D (MultiModuleSdk): direct when-block with lazy ensure*() methods
 * - E (MultiModuleSdkE): ProvisionRegistry with topological sort, eager build
 * - E2 (MultiModuleSdkE2): AutoProvisionRegistry with DFS build-on-demand
 *
 * Run: ./gradlew :benchmark:connectedReleaseAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class MultiModuleBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val config = SdkConfig(debug = false)

    /**
     * Defensive cleanup — if a test fails mid-execution (e.g., ENOSPC on trace write),
     * the SDK object remains initialized. Without this, the NEXT test calling init()
     * would fail with "already initialized" — a cascade failure, not a real bug.
     */
    @After
    fun tearDown() {
        MultiModuleSdk.shutdown()
        MultiModuleSdkE.shutdown()
        MultiModuleSdkE2.shutdown()
        MultiModuleSdkG.shutdown()
        MultiModuleSdkH.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // 1. INIT COLD — create full graph from scratch (6 features)
    // ════════════════════════════════════════════════════════

    @Test
    fun initCold_multiModuleD() = benchmarkRule.measureRepeated {
        MultiModuleSdk.init(config)
        MultiModuleSdk.get<EncryptionApi>()
        MultiModuleSdk.get<HashApi>()
        MultiModuleSdk.get<AuthApi>()
        MultiModuleSdk.get<StorageApi>()
        MultiModuleSdk.get<AnalyticsApi>()
        MultiModuleSdk.get<SyncApi>()
        runWithTimingDisabled { MultiModuleSdk.shutdown() }
    }

    @Test
    fun initCold_multiModuleE() = benchmarkRule.measureRepeated {
        MultiModuleSdkE.init(config, MultiModuleSdkE.Feature.entries.toSet())
        MultiModuleSdkE.get<EncryptionApi>()
        MultiModuleSdkE.get<HashApi>()
        MultiModuleSdkE.get<AuthApi>()
        MultiModuleSdkE.get<StorageApi>()
        MultiModuleSdkE.get<AnalyticsApi>()
        MultiModuleSdkE.get<SyncApi>()
        runWithTimingDisabled { MultiModuleSdkE.shutdown() }
    }

    @Test
    fun initCold_multiModuleE2() = benchmarkRule.measureRepeated {
        MultiModuleSdkE2.init(config)
        MultiModuleSdkE2.get<SyncApi>()       // auto-cascades: Core -> Enc -> Auth -> Stor -> Syn
        MultiModuleSdkE2.get<AnalyticsApi>()   // standalone, builds Ana
        MultiModuleSdkE2.get<EncryptionApi>()  // already cached
        MultiModuleSdkE2.get<HashApi>()        // already cached
        MultiModuleSdkE2.get<AuthApi>()        // already cached
        MultiModuleSdkE2.get<StorageApi>()
        runWithTimingDisabled { MultiModuleSdkE2.shutdown() }
    }

    // ════════════════════════════════════════════════════════
    // 2. RESOLVE FIRST — first resolution from a built graph
    // ════════════════════════════════════════════════════════

    @Test
    fun resolveFirst_multiModuleD() {
        MultiModuleSdk.init(config)
        benchmarkRule.measureRepeated {
            MultiModuleSdk.get<EncryptionApi>()
        }
        MultiModuleSdk.shutdown()
    }

    @Test
    fun resolveFirst_multiModuleE() {
        MultiModuleSdkE.init(config, setOf(MultiModuleSdkE.Feature.ENCRYPTION))
        benchmarkRule.measureRepeated {
            MultiModuleSdkE.get<EncryptionApi>()
        }
        MultiModuleSdkE.shutdown()
    }

    @Test
    fun resolveFirst_multiModuleE2() {
        MultiModuleSdkE2.init(config)
        // First get triggers build (Core + Enc), subsequent iterations hit cache
        benchmarkRule.measureRepeated {
            MultiModuleSdkE2.get<EncryptionApi>()
        }
        MultiModuleSdkE2.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // 3. LAZY INIT — add a feature to a running graph
    // ════════════════════════════════════════════════════════

    // --- Case 1: Analytics (ZERO cross-feature deps) ---

    @Test
    fun lazyInit_noDeps_multiModuleD_analytics() = benchmarkRule.measureRepeated {
        runWithTimingDisabled {
            MultiModuleSdk.init(config)
            MultiModuleSdk.get<EncryptionApi>()  // build base graph
        }
        MultiModuleSdk.get<AnalyticsApi>()  // lazy add standalone
        runWithTimingDisabled { MultiModuleSdk.shutdown() }
    }

    @Test
    fun lazyInit_noDeps_multiModuleE_analytics() = benchmarkRule.measureRepeated {
        runWithTimingDisabled {
            MultiModuleSdkE.init(config, setOf(MultiModuleSdkE.Feature.ENCRYPTION))
        }
        MultiModuleSdkE.getOrInitModule(MultiModuleSdkE.Feature.ANALYTICS)
        MultiModuleSdkE.get<AnalyticsApi>()
        runWithTimingDisabled { MultiModuleSdkE.shutdown() }
    }

    @Test
    fun lazyInit_noDeps_multiModuleE2_analytics() = benchmarkRule.measureRepeated {
        runWithTimingDisabled {
            MultiModuleSdkE2.init(config)
            MultiModuleSdkE2.get<EncryptionApi>()  // build Core + Enc
        }
        MultiModuleSdkE2.get<AnalyticsApi>()  // auto-builds Ana (Core cached)
        runWithTimingDisabled { MultiModuleSdkE2.shutdown() }
    }

    // --- Case 2: Sync (HEAVY deps — Auth + Storage + Encryption cascade) ---

    @Test
    fun lazyInit_cascade_multiModuleD_sync() = benchmarkRule.measureRepeated {
        runWithTimingDisabled {
            MultiModuleSdk.init(config)
            MultiModuleSdk.get<EncryptionApi>()  // build Enc
        }
        MultiModuleSdk.get<SyncApi>()  // cascades: Auth + Stor + Syn
        runWithTimingDisabled { MultiModuleSdk.shutdown() }
    }

    @Test
    fun lazyInit_cascade_multiModuleE_sync() = benchmarkRule.measureRepeated {
        runWithTimingDisabled {
            MultiModuleSdkE.init(config, setOf(MultiModuleSdkE.Feature.ENCRYPTION))
        }
        MultiModuleSdkE.getOrInitModule(MultiModuleSdkE.Feature.SYNC)
        MultiModuleSdkE.get<SyncApi>()
        runWithTimingDisabled { MultiModuleSdkE.shutdown() }
    }

    @Test
    fun lazyInit_cascade_multiModuleE2_sync() = benchmarkRule.measureRepeated {
        runWithTimingDisabled {
            MultiModuleSdkE2.init(config)
            MultiModuleSdkE2.get<EncryptionApi>()  // build Core + Enc
        }
        MultiModuleSdkE2.get<SyncApi>()  // auto-cascades Auth + Stor + Syn
        runWithTimingDisabled { MultiModuleSdkE2.shutdown() }
    }

    // ════════════════════════════════════════════════════════
    // 4. CROSS-FEATURE OP — real work crossing Auth+Storage+Encryption
    //    (full graph built, singletons cached)
    // ════════════════════════════════════════════════════════

    @Test
    fun crossFeatureOp_multiModuleD_sync() {
        MultiModuleSdk.init(config)
        MultiModuleSdk.get<AuthApi>().login("bench", "pass")
        val sync = MultiModuleSdk.get<SyncApi>()
        benchmarkRule.measureRepeated {
            sync.sync()
        }
        MultiModuleSdk.shutdown()
    }

    @Test
    fun crossFeatureOp_multiModuleE_sync() {
        MultiModuleSdkE.init(config, MultiModuleSdkE.Feature.entries.toSet())
        MultiModuleSdkE.get<AuthApi>().login("bench", "pass")
        val sync = MultiModuleSdkE.get<SyncApi>()
        benchmarkRule.measureRepeated {
            sync.sync()
        }
        MultiModuleSdkE.shutdown()
    }

    @Test
    fun crossFeatureOp_multiModuleE2_sync() {
        MultiModuleSdkE2.init(config)
        MultiModuleSdkE2.get<AuthApi>().login("bench", "pass")
        val sync = MultiModuleSdkE2.get<SyncApi>()
        benchmarkRule.measureRepeated {
            sync.sync()
        }
        MultiModuleSdkE2.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // 5. PATTERN G — Factory Functions (no DaggerXxx imports)
    // ════════════════════════════════════════════════════════

    @Test
    fun initCold_multiModuleG() = benchmarkRule.measureRepeated {
        MultiModuleSdkG.init(config)
        MultiModuleSdkG.get<EncryptionApi>()
        MultiModuleSdkG.get<HashApi>()
        MultiModuleSdkG.get<AuthApi>()
        MultiModuleSdkG.get<StorageApi>()
        MultiModuleSdkG.get<AnalyticsApi>()
        MultiModuleSdkG.get<SyncApi>()
        runWithTimingDisabled { MultiModuleSdkG.shutdown() }
    }

    @Test
    fun resolveFirst_multiModuleG() {
        MultiModuleSdkG.init(config)
        benchmarkRule.measureRepeated {
            MultiModuleSdkG.get<EncryptionApi>()
        }
        MultiModuleSdkG.shutdown()
    }

    @Test
    fun lazyInit_noDeps_multiModuleG_analytics() = benchmarkRule.measureRepeated {
        runWithTimingDisabled {
            MultiModuleSdkG.init(config)
            MultiModuleSdkG.get<EncryptionApi>()
        }
        MultiModuleSdkG.get<AnalyticsApi>()
        runWithTimingDisabled { MultiModuleSdkG.shutdown() }
    }

    @Test
    fun lazyInit_cascade_multiModuleG_sync() = benchmarkRule.measureRepeated {
        runWithTimingDisabled {
            MultiModuleSdkG.init(config)
            MultiModuleSdkG.get<EncryptionApi>()
        }
        MultiModuleSdkG.get<SyncApi>()
        runWithTimingDisabled { MultiModuleSdkG.shutdown() }
    }

    @Test
    fun crossFeatureOp_multiModuleG_sync() {
        MultiModuleSdkG.init(config)
        MultiModuleSdkG.get<AuthApi>().login("bench", "pass")
        val sync = MultiModuleSdkG.get<SyncApi>()
        benchmarkRule.measureRepeated {
            sync.sync()
        }
        MultiModuleSdkG.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // 6. PATTERN H — Auto-Discovery FeatureProviders
    //    (zero central editing, DFS via resolver.provision())
    // ════════════════════════════════════════════════════════

    @Test
    fun initCold_multiModuleH() = benchmarkRule.measureRepeated {
        MultiModuleSdkH.init(config)
        MultiModuleSdkH.get<EncryptionApi>()
        MultiModuleSdkH.get<HashApi>()
        MultiModuleSdkH.get<AuthApi>()
        MultiModuleSdkH.get<StorageApi>()
        MultiModuleSdkH.get<AnalyticsApi>()
        MultiModuleSdkH.get<SyncApi>()
        runWithTimingDisabled { MultiModuleSdkH.shutdown() }
    }

    @Test
    fun resolveFirst_multiModuleH() {
        MultiModuleSdkH.init(config)
        benchmarkRule.measureRepeated {
            MultiModuleSdkH.get<EncryptionApi>()
        }
        MultiModuleSdkH.shutdown()
    }

    @Test
    fun lazyInit_noDeps_multiModuleH_analytics() = benchmarkRule.measureRepeated {
        runWithTimingDisabled {
            MultiModuleSdkH.init(config)
            MultiModuleSdkH.get<EncryptionApi>()
        }
        MultiModuleSdkH.get<AnalyticsApi>()
        runWithTimingDisabled { MultiModuleSdkH.shutdown() }
    }

    @Test
    fun lazyInit_cascade_multiModuleH_sync() = benchmarkRule.measureRepeated {
        runWithTimingDisabled {
            MultiModuleSdkH.init(config)
            MultiModuleSdkH.get<EncryptionApi>()
        }
        MultiModuleSdkH.get<SyncApi>()
        runWithTimingDisabled { MultiModuleSdkH.shutdown() }
    }

    @Test
    fun crossFeatureOp_multiModuleH_sync() {
        MultiModuleSdkH.init(config)
        MultiModuleSdkH.get<AuthApi>().login("bench", "pass")
        val sync = MultiModuleSdkH.get<SyncApi>()
        benchmarkRule.measureRepeated {
            sync.sync()
        }
        MultiModuleSdkH.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // 7. STRESS — Init/Shutdown cycle
    //    Measures overhead of repeated init→get one service→shutdown
    // ════════════════════════════════════════════════════════

    @Test
    fun stress_initShutdownCycle_multiModuleD() = benchmarkRule.measureRepeated {
        MultiModuleSdk.init(config)
        MultiModuleSdk.get<EncryptionApi>()
        MultiModuleSdk.shutdown()
    }

    @Test
    fun stress_initShutdownCycle_multiModuleE() = benchmarkRule.measureRepeated {
        MultiModuleSdkE.init(config, setOf(MultiModuleSdkE.Feature.ENCRYPTION))
        MultiModuleSdkE.get<EncryptionApi>()
        MultiModuleSdkE.shutdown()
    }

    @Test
    fun stress_initShutdownCycle_multiModuleE2() = benchmarkRule.measureRepeated {
        MultiModuleSdkE2.init(config)
        MultiModuleSdkE2.get<EncryptionApi>()
        MultiModuleSdkE2.shutdown()
    }

    @Test
    fun stress_initShutdownCycle_multiModuleG() = benchmarkRule.measureRepeated {
        MultiModuleSdkG.init(config)
        MultiModuleSdkG.get<EncryptionApi>()
        MultiModuleSdkG.shutdown()
    }

    @Test
    fun stress_initShutdownCycle_multiModuleH() = benchmarkRule.measureRepeated {
        MultiModuleSdkH.init(config)
        MultiModuleSdkH.get<EncryptionApi>()
        MultiModuleSdkH.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // 8. STRESS — Concurrent resolve
    //    Multiple threads calling get<T>() simultaneously on a built graph
    // ════════════════════════════════════════════════════════

    @Test
    fun stress_concurrentResolve_multiModuleD() {
        MultiModuleSdk.init(config)
        MultiModuleSdk.get<SyncApi>()
        MultiModuleSdk.get<AnalyticsApi>()
        benchmarkRule.measureRepeated {
            val threads = listOf(
                Thread { MultiModuleSdk.get<EncryptionApi>() },
                Thread { MultiModuleSdk.get<AuthApi>() },
                Thread { MultiModuleSdk.get<StorageApi>() },
                Thread { MultiModuleSdk.get<SyncApi>() },
            )
            threads.forEach { it.start() }
            threads.forEach { it.join() }
        }
        MultiModuleSdk.shutdown()
    }

    @Test
    fun stress_concurrentResolve_multiModuleE() {
        MultiModuleSdkE.init(config, MultiModuleSdkE.Feature.entries.toSet())
        MultiModuleSdkE.get<SyncApi>()
        MultiModuleSdkE.get<AnalyticsApi>()
        benchmarkRule.measureRepeated {
            val threads = listOf(
                Thread { MultiModuleSdkE.get<EncryptionApi>() },
                Thread { MultiModuleSdkE.get<AuthApi>() },
                Thread { MultiModuleSdkE.get<StorageApi>() },
                Thread { MultiModuleSdkE.get<SyncApi>() },
            )
            threads.forEach { it.start() }
            threads.forEach { it.join() }
        }
        MultiModuleSdkE.shutdown()
    }

    @Test
    fun stress_concurrentResolve_multiModuleE2() {
        MultiModuleSdkE2.init(config)
        MultiModuleSdkE2.get<SyncApi>()
        MultiModuleSdkE2.get<AnalyticsApi>()
        benchmarkRule.measureRepeated {
            val threads = listOf(
                Thread { MultiModuleSdkE2.get<EncryptionApi>() },
                Thread { MultiModuleSdkE2.get<AuthApi>() },
                Thread { MultiModuleSdkE2.get<StorageApi>() },
                Thread { MultiModuleSdkE2.get<SyncApi>() },
            )
            threads.forEach { it.start() }
            threads.forEach { it.join() }
        }
        MultiModuleSdkE2.shutdown()
    }

    @Test
    fun stress_concurrentResolve_multiModuleG() {
        MultiModuleSdkG.init(config)
        MultiModuleSdkG.get<SyncApi>()
        MultiModuleSdkG.get<AnalyticsApi>()
        benchmarkRule.measureRepeated {
            val threads = listOf(
                Thread { MultiModuleSdkG.get<EncryptionApi>() },
                Thread { MultiModuleSdkG.get<AuthApi>() },
                Thread { MultiModuleSdkG.get<StorageApi>() },
                Thread { MultiModuleSdkG.get<SyncApi>() },
            )
            threads.forEach { it.start() }
            threads.forEach { it.join() }
        }
        MultiModuleSdkG.shutdown()
    }

    @Test
    fun stress_concurrentResolve_multiModuleH() {
        MultiModuleSdkH.init(config)
        MultiModuleSdkH.get<SyncApi>()
        MultiModuleSdkH.get<AnalyticsApi>()
        benchmarkRule.measureRepeated {
            val threads = listOf(
                Thread { MultiModuleSdkH.get<EncryptionApi>() },
                Thread { MultiModuleSdkH.get<AuthApi>() },
                Thread { MultiModuleSdkH.get<StorageApi>() },
                Thread { MultiModuleSdkH.get<SyncApi>() },
            )
            threads.forEach { it.start() }
            threads.forEach { it.join() }
        }
        MultiModuleSdkH.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // 9. STRESS — Resolve all sequential
    //    Resolve all 6 services one by one from a built graph
    // ════════════════════════════════════════════════════════

    @Test
    fun stress_resolveAllSequential_multiModuleD() {
        MultiModuleSdk.init(config)
        MultiModuleSdk.get<SyncApi>()
        MultiModuleSdk.get<AnalyticsApi>()
        benchmarkRule.measureRepeated {
            MultiModuleSdk.get<EncryptionApi>()
            MultiModuleSdk.get<HashApi>()
            MultiModuleSdk.get<AuthApi>()
            MultiModuleSdk.get<StorageApi>()
            MultiModuleSdk.get<AnalyticsApi>()
            MultiModuleSdk.get<SyncApi>()
        }
        MultiModuleSdk.shutdown()
    }

    @Test
    fun stress_resolveAllSequential_multiModuleE() {
        MultiModuleSdkE.init(config, MultiModuleSdkE.Feature.entries.toSet())
        MultiModuleSdkE.get<SyncApi>()
        MultiModuleSdkE.get<AnalyticsApi>()
        benchmarkRule.measureRepeated {
            MultiModuleSdkE.get<EncryptionApi>()
            MultiModuleSdkE.get<HashApi>()
            MultiModuleSdkE.get<AuthApi>()
            MultiModuleSdkE.get<StorageApi>()
            MultiModuleSdkE.get<AnalyticsApi>()
            MultiModuleSdkE.get<SyncApi>()
        }
        MultiModuleSdkE.shutdown()
    }

    @Test
    fun stress_resolveAllSequential_multiModuleE2() {
        MultiModuleSdkE2.init(config)
        MultiModuleSdkE2.get<SyncApi>()
        MultiModuleSdkE2.get<AnalyticsApi>()
        benchmarkRule.measureRepeated {
            MultiModuleSdkE2.get<EncryptionApi>()
            MultiModuleSdkE2.get<HashApi>()
            MultiModuleSdkE2.get<AuthApi>()
            MultiModuleSdkE2.get<StorageApi>()
            MultiModuleSdkE2.get<AnalyticsApi>()
            MultiModuleSdkE2.get<SyncApi>()
        }
        MultiModuleSdkE2.shutdown()
    }

    @Test
    fun stress_resolveAllSequential_multiModuleG() {
        MultiModuleSdkG.init(config)
        MultiModuleSdkG.get<SyncApi>()
        MultiModuleSdkG.get<AnalyticsApi>()
        benchmarkRule.measureRepeated {
            MultiModuleSdkG.get<EncryptionApi>()
            MultiModuleSdkG.get<HashApi>()
            MultiModuleSdkG.get<AuthApi>()
            MultiModuleSdkG.get<StorageApi>()
            MultiModuleSdkG.get<AnalyticsApi>()
            MultiModuleSdkG.get<SyncApi>()
        }
        MultiModuleSdkG.shutdown()
    }

    @Test
    fun stress_resolveAllSequential_multiModuleH() {
        MultiModuleSdkH.init(config)
        MultiModuleSdkH.get<SyncApi>()
        MultiModuleSdkH.get<AnalyticsApi>()
        benchmarkRule.measureRepeated {
            MultiModuleSdkH.get<EncryptionApi>()
            MultiModuleSdkH.get<HashApi>()
            MultiModuleSdkH.get<AuthApi>()
            MultiModuleSdkH.get<StorageApi>()
            MultiModuleSdkH.get<AnalyticsApi>()
            MultiModuleSdkH.get<SyncApi>()
        }
        MultiModuleSdkH.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // 10. STRESS — Selective init (1 of 6)
    //     Only request 1 feature. Measures that other features are NOT built
    // ════════════════════════════════════════════════════════

    @Test
    fun stress_selectiveInit_multiModuleD() = benchmarkRule.measureRepeated {
        MultiModuleSdk.init(config)
        MultiModuleSdk.get<AnalyticsApi>()  // only Analytics + Core, nothing else
        runWithTimingDisabled { MultiModuleSdk.shutdown() }
    }

    @Test
    fun stress_selectiveInit_multiModuleE() = benchmarkRule.measureRepeated {
        MultiModuleSdkE.init(config, setOf(MultiModuleSdkE.Feature.ANALYTICS))
        MultiModuleSdkE.get<AnalyticsApi>()  // only Analytics + Core, nothing else
        runWithTimingDisabled { MultiModuleSdkE.shutdown() }
    }

    @Test
    fun stress_selectiveInit_multiModuleE2() = benchmarkRule.measureRepeated {
        MultiModuleSdkE2.init(config)
        MultiModuleSdkE2.get<AnalyticsApi>()  // only Analytics + Core, nothing else
        runWithTimingDisabled { MultiModuleSdkE2.shutdown() }
    }

    @Test
    fun stress_selectiveInit_multiModuleG() = benchmarkRule.measureRepeated {
        MultiModuleSdkG.init(config)
        MultiModuleSdkG.get<AnalyticsApi>()  // only Analytics + Core, nothing else
        runWithTimingDisabled { MultiModuleSdkG.shutdown() }
    }

    @Test
    fun stress_selectiveInit_multiModuleH() = benchmarkRule.measureRepeated {
        MultiModuleSdkH.init(config)
        MultiModuleSdkH.get<AnalyticsApi>()  // only Analytics + Core, nothing else
        runWithTimingDisabled { MultiModuleSdkH.shutdown() }
    }

    // ════════════════════════════════════════════════════════
    // 11. STRESS — Re-init after shutdown
    //     Full cycle: init→get all→shutdown→init→get all. Second cycle must be clean
    // ════════════════════════════════════════════════════════

    @Test
    fun stress_reInitAfterShutdown_multiModuleD() = benchmarkRule.measureRepeated {
        // First cycle
        MultiModuleSdk.init(config)
        MultiModuleSdk.get<SyncApi>()
        MultiModuleSdk.get<AnalyticsApi>()
        MultiModuleSdk.shutdown()
        // Second cycle — must be clean
        MultiModuleSdk.init(config)
        MultiModuleSdk.get<SyncApi>()
        MultiModuleSdk.get<AnalyticsApi>()
        MultiModuleSdk.shutdown()
    }

    @Test
    fun stress_reInitAfterShutdown_multiModuleE() = benchmarkRule.measureRepeated {
        // First cycle
        MultiModuleSdkE.init(config, MultiModuleSdkE.Feature.entries.toSet())
        MultiModuleSdkE.get<SyncApi>()
        MultiModuleSdkE.get<AnalyticsApi>()
        MultiModuleSdkE.shutdown()
        // Second cycle — must be clean
        MultiModuleSdkE.init(config, MultiModuleSdkE.Feature.entries.toSet())
        MultiModuleSdkE.get<SyncApi>()
        MultiModuleSdkE.get<AnalyticsApi>()
        MultiModuleSdkE.shutdown()
    }

    @Test
    fun stress_reInitAfterShutdown_multiModuleE2() = benchmarkRule.measureRepeated {
        // First cycle
        MultiModuleSdkE2.init(config)
        MultiModuleSdkE2.get<SyncApi>()
        MultiModuleSdkE2.get<AnalyticsApi>()
        MultiModuleSdkE2.shutdown()
        // Second cycle — must be clean
        MultiModuleSdkE2.init(config)
        MultiModuleSdkE2.get<SyncApi>()
        MultiModuleSdkE2.get<AnalyticsApi>()
        MultiModuleSdkE2.shutdown()
    }

    @Test
    fun stress_reInitAfterShutdown_multiModuleG() = benchmarkRule.measureRepeated {
        // First cycle
        MultiModuleSdkG.init(config)
        MultiModuleSdkG.get<SyncApi>()
        MultiModuleSdkG.get<AnalyticsApi>()
        MultiModuleSdkG.shutdown()
        // Second cycle — must be clean
        MultiModuleSdkG.init(config)
        MultiModuleSdkG.get<SyncApi>()
        MultiModuleSdkG.get<AnalyticsApi>()
        MultiModuleSdkG.shutdown()
    }

    @Test
    fun stress_reInitAfterShutdown_multiModuleH() = benchmarkRule.measureRepeated {
        // First cycle
        MultiModuleSdkH.init(config)
        MultiModuleSdkH.get<SyncApi>()
        MultiModuleSdkH.get<AnalyticsApi>()
        MultiModuleSdkH.shutdown()
        // Second cycle — must be clean
        MultiModuleSdkH.init(config)
        MultiModuleSdkH.get<SyncApi>()
        MultiModuleSdkH.get<AnalyticsApi>()
        MultiModuleSdkH.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // 12. STRESS — Incremental build (add features one by one)
    //     Start with 1 feature, add more incrementally. Measures lazy cascade cost
    // ════════════════════════════════════════════════════════

    @Test
    fun stress_incrementalBuild_multiModuleD() = benchmarkRule.measureRepeated {
        MultiModuleSdk.init(config)
        MultiModuleSdk.get<EncryptionApi>()    // builds Core + Enc
        MultiModuleSdk.get<AuthApi>()           // builds Auth (Enc cached)
        MultiModuleSdk.get<StorageApi>()        // builds Stor (Enc cached)
        MultiModuleSdk.get<AnalyticsApi>()      // builds Ana (Core cached)
        MultiModuleSdk.get<SyncApi>()           // builds Syn (Auth+Stor+Enc cached)
        MultiModuleSdk.get<HashApi>()           // already cached
        runWithTimingDisabled { MultiModuleSdk.shutdown() }
    }

    @Test
    fun stress_incrementalBuild_multiModuleE() = benchmarkRule.measureRepeated {
        MultiModuleSdkE.init(config, MultiModuleSdkE.Feature.entries.toSet())
        MultiModuleSdkE.get<EncryptionApi>()    // builds Core + Enc
        MultiModuleSdkE.get<AuthApi>()           // builds Auth (Enc cached)
        MultiModuleSdkE.get<StorageApi>()        // builds Stor (Enc cached)
        MultiModuleSdkE.get<AnalyticsApi>()      // builds Ana (Core cached)
        MultiModuleSdkE.get<SyncApi>()           // builds Syn (Auth+Stor+Enc cached)
        MultiModuleSdkE.get<HashApi>()           // already cached
        runWithTimingDisabled { MultiModuleSdkE.shutdown() }
    }

    @Test
    fun stress_incrementalBuild_multiModuleE2() = benchmarkRule.measureRepeated {
        MultiModuleSdkE2.init(config)
        MultiModuleSdkE2.get<EncryptionApi>()    // builds Core + Enc
        MultiModuleSdkE2.get<AuthApi>()           // builds Auth (Enc cached)
        MultiModuleSdkE2.get<StorageApi>()        // builds Stor (Enc cached)
        MultiModuleSdkE2.get<AnalyticsApi>()      // builds Ana (Core cached)
        MultiModuleSdkE2.get<SyncApi>()           // builds Syn (Auth+Stor+Enc cached)
        MultiModuleSdkE2.get<HashApi>()           // already cached
        runWithTimingDisabled { MultiModuleSdkE2.shutdown() }
    }

    @Test
    fun stress_incrementalBuild_multiModuleG() = benchmarkRule.measureRepeated {
        MultiModuleSdkG.init(config)
        MultiModuleSdkG.get<EncryptionApi>()    // builds Core + Enc
        MultiModuleSdkG.get<AuthApi>()           // builds Auth (Enc cached)
        MultiModuleSdkG.get<StorageApi>()        // builds Stor (Enc cached)
        MultiModuleSdkG.get<AnalyticsApi>()      // builds Ana (Core cached)
        MultiModuleSdkG.get<SyncApi>()           // builds Syn (Auth+Stor+Enc cached)
        MultiModuleSdkG.get<HashApi>()           // already cached
        runWithTimingDisabled { MultiModuleSdkG.shutdown() }
    }

    @Test
    fun stress_incrementalBuild_multiModuleH() = benchmarkRule.measureRepeated {
        MultiModuleSdkH.init(config)
        MultiModuleSdkH.get<EncryptionApi>()    // builds Core + Enc
        MultiModuleSdkH.get<AuthApi>()           // builds Auth (Enc cached)
        MultiModuleSdkH.get<StorageApi>()        // builds Stor (Enc cached)
        MultiModuleSdkH.get<AnalyticsApi>()      // builds Ana (Core cached)
        MultiModuleSdkH.get<SyncApi>()           // builds Syn (Auth+Stor+Enc cached)
        MultiModuleSdkH.get<HashApi>()           // already cached
        runWithTimingDisabled { MultiModuleSdkH.shutdown() }
    }
}

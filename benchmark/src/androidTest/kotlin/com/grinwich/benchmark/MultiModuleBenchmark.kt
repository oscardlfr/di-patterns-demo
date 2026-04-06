package com.grinwich.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grinwich.sdk.api.*
import com.grinwich.sdk.wiring.MultiModuleSdk
import com.grinwich.sdk.wiring.e.MultiModuleSdkE
import com.grinwich.sdk.wiring.e2.MultiModuleSdkE2
import com.grinwich.sdk.wiring.g.MultiModuleSdkG
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
}

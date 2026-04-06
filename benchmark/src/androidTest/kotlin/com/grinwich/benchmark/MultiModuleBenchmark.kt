package com.grinwich.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grinwich.sdk.api.*
import com.grinwich.sdk.wiring.MultiModuleSdk
import com.grinwich.sdk.wiring.e.MultiModuleSdkE
import com.grinwich.sdk.wiring.e2.MultiModuleSdkE2
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
    private val noopLogger: SdkLogger = object : SdkLogger {
        override fun d(tag: String, msg: String) {}
        override fun e(tag: String, msg: String, throwable: Throwable?) {}
    }

    // ════════════════════════════════════════════════════════
    // 1. INIT COLD — create full graph from scratch (6 features)
    // ════════════════════════════════════════════════════════

    @Test
    fun initCold_multiModuleD() = benchmarkRule.measureRepeated {
        MultiModuleSdk.init(config, noopLogger)
        MultiModuleSdk.get<EncryptionService>()
        MultiModuleSdk.get<HashService>()
        MultiModuleSdk.get<AuthService>()
        MultiModuleSdk.get<SecureStorageService>()
        MultiModuleSdk.get<AnalyticsService>()
        MultiModuleSdk.get<SyncService>()
        runWithTimingDisabled { MultiModuleSdk.shutdown() }
    }

    @Test
    fun initCold_multiModuleE() = benchmarkRule.measureRepeated {
        MultiModuleSdkE.init(config, MultiModuleSdkE.Feature.entries.toSet(), noopLogger)
        MultiModuleSdkE.get<EncryptionService>()
        MultiModuleSdkE.get<HashService>()
        MultiModuleSdkE.get<AuthService>()
        MultiModuleSdkE.get<SecureStorageService>()
        MultiModuleSdkE.get<AnalyticsService>()
        MultiModuleSdkE.get<SyncService>()
        runWithTimingDisabled { MultiModuleSdkE.shutdown() }
    }

    @Test
    fun initCold_multiModuleE2() = benchmarkRule.measureRepeated {
        MultiModuleSdkE2.init(config, noopLogger)
        MultiModuleSdkE2.get<SyncService>()       // auto-cascades: Core -> Enc -> Auth -> Stor -> Syn
        MultiModuleSdkE2.get<AnalyticsService>()   // standalone, builds Ana
        MultiModuleSdkE2.get<EncryptionService>()  // already cached
        MultiModuleSdkE2.get<HashService>()        // already cached
        MultiModuleSdkE2.get<AuthService>()        // already cached
        MultiModuleSdkE2.get<SecureStorageService>()
        runWithTimingDisabled { MultiModuleSdkE2.shutdown() }
    }

    // ════════════════════════════════════════════════════════
    // 2. RESOLVE FIRST — first resolution from a built graph
    // ════════════════════════════════════════════════════════

    @Test
    fun resolveFirst_multiModuleD() {
        MultiModuleSdk.init(config, noopLogger)
        benchmarkRule.measureRepeated {
            MultiModuleSdk.get<EncryptionService>()
        }
        MultiModuleSdk.shutdown()
    }

    @Test
    fun resolveFirst_multiModuleE() {
        MultiModuleSdkE.init(config, setOf(MultiModuleSdkE.Feature.ENCRYPTION), noopLogger)
        benchmarkRule.measureRepeated {
            MultiModuleSdkE.get<EncryptionService>()
        }
        MultiModuleSdkE.shutdown()
    }

    @Test
    fun resolveFirst_multiModuleE2() {
        MultiModuleSdkE2.init(config, noopLogger)
        // First get triggers build (Core + Enc), subsequent iterations hit cache
        benchmarkRule.measureRepeated {
            MultiModuleSdkE2.get<EncryptionService>()
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
            MultiModuleSdk.init(config, noopLogger)
            MultiModuleSdk.get<EncryptionService>()  // build base graph
        }
        MultiModuleSdk.get<AnalyticsService>()  // lazy add standalone
        runWithTimingDisabled { MultiModuleSdk.shutdown() }
    }

    @Test
    fun lazyInit_noDeps_multiModuleE_analytics() = benchmarkRule.measureRepeated {
        runWithTimingDisabled {
            MultiModuleSdkE.init(config, setOf(MultiModuleSdkE.Feature.ENCRYPTION), noopLogger)
        }
        MultiModuleSdkE.getOrInitModule(MultiModuleSdkE.Feature.ANALYTICS)
        MultiModuleSdkE.get<AnalyticsService>()
        runWithTimingDisabled { MultiModuleSdkE.shutdown() }
    }

    @Test
    fun lazyInit_noDeps_multiModuleE2_analytics() = benchmarkRule.measureRepeated {
        runWithTimingDisabled {
            MultiModuleSdkE2.init(config, noopLogger)
            MultiModuleSdkE2.get<EncryptionService>()  // build Core + Enc
        }
        MultiModuleSdkE2.get<AnalyticsService>()  // auto-builds Ana (Core cached)
        runWithTimingDisabled { MultiModuleSdkE2.shutdown() }
    }

    // --- Case 2: Sync (HEAVY deps — Auth + Storage + Encryption cascade) ---

    @Test
    fun lazyInit_cascade_multiModuleD_sync() = benchmarkRule.measureRepeated {
        runWithTimingDisabled {
            MultiModuleSdk.init(config, noopLogger)
            MultiModuleSdk.get<EncryptionService>()  // build Enc
        }
        MultiModuleSdk.get<SyncService>()  // cascades: Auth + Stor + Syn
        runWithTimingDisabled { MultiModuleSdk.shutdown() }
    }

    @Test
    fun lazyInit_cascade_multiModuleE_sync() = benchmarkRule.measureRepeated {
        runWithTimingDisabled {
            MultiModuleSdkE.init(config, setOf(MultiModuleSdkE.Feature.ENCRYPTION), noopLogger)
        }
        MultiModuleSdkE.getOrInitModule(MultiModuleSdkE.Feature.SYNC)
        MultiModuleSdkE.get<SyncService>()
        runWithTimingDisabled { MultiModuleSdkE.shutdown() }
    }

    @Test
    fun lazyInit_cascade_multiModuleE2_sync() = benchmarkRule.measureRepeated {
        runWithTimingDisabled {
            MultiModuleSdkE2.init(config, noopLogger)
            MultiModuleSdkE2.get<EncryptionService>()  // build Core + Enc
        }
        MultiModuleSdkE2.get<SyncService>()  // auto-cascades Auth + Stor + Syn
        runWithTimingDisabled { MultiModuleSdkE2.shutdown() }
    }

    // ════════════════════════════════════════════════════════
    // 4. CROSS-FEATURE OP — real work crossing Auth+Storage+Encryption
    //    (full graph built, singletons cached)
    // ════════════════════════════════════════════════════════

    @Test
    fun crossFeatureOp_multiModuleD_sync() {
        MultiModuleSdk.init(config, noopLogger)
        MultiModuleSdk.get<AuthService>().login("bench", "pass")
        val sync = MultiModuleSdk.get<SyncService>()
        benchmarkRule.measureRepeated {
            sync.sync()
        }
        MultiModuleSdk.shutdown()
    }

    @Test
    fun crossFeatureOp_multiModuleE_sync() {
        MultiModuleSdkE.init(config, MultiModuleSdkE.Feature.entries.toSet(), noopLogger)
        MultiModuleSdkE.get<AuthService>().login("bench", "pass")
        val sync = MultiModuleSdkE.get<SyncService>()
        benchmarkRule.measureRepeated {
            sync.sync()
        }
        MultiModuleSdkE.shutdown()
    }

    @Test
    fun crossFeatureOp_multiModuleE2_sync() {
        MultiModuleSdkE2.init(config, noopLogger)
        MultiModuleSdkE2.get<AuthService>().login("bench", "pass")
        val sync = MultiModuleSdkE2.get<SyncService>()
        benchmarkRule.measureRepeated {
            sync.sync()
        }
        MultiModuleSdkE2.shutdown()
    }
}

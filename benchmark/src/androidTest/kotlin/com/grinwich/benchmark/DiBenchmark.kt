package com.grinwich.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grinwich.sdk.api.*
import com.grinwich.sdk.api.StorageBackend
import com.grinwich.sdk.daggerb.DaggerBSdk
import com.grinwich.sdk.daggerc.DaggerCSdk
import com.grinwich.sdk.impl.KoinSdk
import com.grinwich.sdk.impl.SdkModule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Microbenchmarks for monolithic SDK patterns using REAL facades.
 *
 * Each test calls the actual SDK facade (init/get/shutdown) — same API
 * a real consumer would use. No internal Components exposed.
 *
 * Patterns:
 * - B: Per-Feature Components (DaggerBSdk)
 * - C: ServiceLoader Discovery (DaggerCSdk)
 * - Koin: Service Locator (KoinSdk)
 * - Hybrid: Koin SDK + Dagger bridge (KoinSdk + BenchBridgeComponent)
 *
 * Multi-module patterns (D, E, E2, G, H) are in MultiModuleBenchmark.kt.
 *
 * Run: ./gradlew :benchmark:connectedReleaseAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class DiBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val config = SdkConfig(debug = false)

    @After
    fun tearDown() {
        DaggerBSdk.shutdown()
        DaggerCSdk.shutdown()
        KoinSdk.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // 1. DAGGER B — Per-Feature Components + CoreApis
    // ════════════════════════════════════════════════════════

    @Test
    fun initCold_daggerB() = benchmarkRule.measureRepeated {
        DaggerBSdk.init(testContext, config, DaggerBSdk.Feature.entries.toSet())
        DaggerBSdk.get<EncryptionApi>()
        DaggerBSdk.get<HashApi>()
        DaggerBSdk.get<AuthApi>()
        DaggerBSdk.get<StorageApi>()
        DaggerBSdk.get<AnalyticsApi>()
        DaggerBSdk.get<SyncApi>()
        runWithMeasurementDisabled { DaggerBSdk.shutdown() }
    }

    @Test
    fun resolveFirst_daggerB() {
        DaggerBSdk.init(testContext, config, setOf(DaggerBSdk.Feature.ENCRYPTION))
        benchmarkRule.measureRepeated {
            DaggerBSdk.get<EncryptionApi>()
        }
        DaggerBSdk.shutdown()
    }

    @Test
    fun lazyInit_noDeps_daggerB_analytics() = benchmarkRule.measureRepeated {
        runWithMeasurementDisabled {
            DaggerBSdk.init(testContext, config, setOf(DaggerBSdk.Feature.ENCRYPTION))
        }
        DaggerBSdk.getOrInitModule(DaggerBSdk.Feature.ANALYTICS)
        DaggerBSdk.get<AnalyticsApi>()
        runWithMeasurementDisabled { DaggerBSdk.shutdown() }
    }

    @Test
    fun lazyInit_cascade_daggerB_sync() = benchmarkRule.measureRepeated {
        runWithMeasurementDisabled {
            DaggerBSdk.init(testContext, config, setOf(DaggerBSdk.Feature.ENCRYPTION))
        }
        DaggerBSdk.getOrInitModule(DaggerBSdk.Feature.SYNC)
        DaggerBSdk.get<SyncApi>()
        runWithMeasurementDisabled { DaggerBSdk.shutdown() }
    }

    @Test
    fun crossFeatureOp_daggerB_sync() {
        DaggerBSdk.init(testContext, config, DaggerBSdk.Feature.entries.toSet())
        DaggerBSdk.get<AuthApi>().login("bench", "pass")
        val sync = DaggerBSdk.get<SyncApi>()
        benchmarkRule.measureRepeated {
            runBlocking { sync.sync() }
        }
        DaggerBSdk.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // 2. DAGGER C — ServiceLoader Discovery
    // ════════════════════════════════════════════════════════

    @Test
    fun initCold_daggerC() = benchmarkRule.measureRepeated {
        DaggerCSdk.init(testContext, config, setOf("encryption", "auth", "storage", "analytics", "sync"))
        DaggerCSdk.get<EncryptionApi>()
        DaggerCSdk.get<HashApi>()
        DaggerCSdk.get<AuthApi>()
        DaggerCSdk.get<StorageApi>()
        DaggerCSdk.get<AnalyticsApi>()
        DaggerCSdk.get<SyncApi>()
        runWithMeasurementDisabled { DaggerCSdk.shutdown() }
    }

    @Test
    fun resolveFirst_daggerC() {
        DaggerCSdk.init(testContext, config, setOf("encryption"))
        benchmarkRule.measureRepeated {
            DaggerCSdk.get<EncryptionApi>()
        }
        DaggerCSdk.shutdown()
    }

    @Test
    fun lazyInit_noDeps_daggerC_analytics() = benchmarkRule.measureRepeated {
        runWithMeasurementDisabled {
            DaggerCSdk.init(testContext, config, setOf("encryption"))
        }
        DaggerCSdk.getOrInitModule("analytics")
        DaggerCSdk.get<AnalyticsApi>()
        runWithMeasurementDisabled { DaggerCSdk.shutdown() }
    }

    @Test
    fun lazyInit_cascade_daggerC_sync() = benchmarkRule.measureRepeated {
        runWithMeasurementDisabled {
            DaggerCSdk.init(testContext, config, setOf("encryption"))
        }
        DaggerCSdk.getOrInitModule("sync")
        DaggerCSdk.get<SyncApi>()
        runWithMeasurementDisabled { DaggerCSdk.shutdown() }
    }

    @Test
    fun crossFeatureOp_daggerC_sync() {
        DaggerCSdk.init(testContext, config, setOf("encryption", "auth", "storage", "analytics", "sync"))
        DaggerCSdk.get<AuthApi>().login("bench", "pass")
        val sync = DaggerCSdk.get<SyncApi>()
        benchmarkRule.measureRepeated {
            runBlocking { sync.sync() }
        }
        DaggerCSdk.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // 3. KOIN — Service Locator
    // ════════════════════════════════════════════════════════

    @Test
    fun initCold_koin() = benchmarkRule.measureRepeated {
        KoinSdk.init(testContext, setOf(SdkModule.Encryption.Default, SdkModule.Auth.Default, SdkModule.Storage.Secure, SdkModule.Analytics.Default, SdkModule.Sync.Default), config)
        KoinSdk.get<EncryptionApi>()
        KoinSdk.get<HashApi>()
        KoinSdk.get<AuthApi>()
        KoinSdk.get<StorageApi>()
        KoinSdk.get<AnalyticsApi>()
        KoinSdk.get<SyncApi>()
        runWithMeasurementDisabled { KoinSdk.shutdown() }
    }

    @Test
    fun resolveFirst_koin() {
        KoinSdk.init(testContext, setOf(SdkModule.Encryption.Default), config)
        benchmarkRule.measureRepeated {
            KoinSdk.get<EncryptionApi>()
        }
        KoinSdk.shutdown()
    }

    @Test
    fun lazyInit_noDeps_koin_analytics() = benchmarkRule.measureRepeated {
        runWithMeasurementDisabled {
            KoinSdk.init(testContext, setOf(SdkModule.Encryption.Default), config)
        }
        KoinSdk.getOrInitModule(SdkModule.Analytics.Default)
        KoinSdk.get<AnalyticsApi>()
        runWithMeasurementDisabled { KoinSdk.shutdown() }
    }

    @Test
    fun lazyInit_cascade_koin_sync() = benchmarkRule.measureRepeated {
        runWithMeasurementDisabled {
            KoinSdk.init(testContext, setOf(SdkModule.Encryption.Default), config)
        }
        KoinSdk.getOrInitModule(SdkModule.Sync.Default)
        KoinSdk.get<SyncApi>()
        runWithMeasurementDisabled { KoinSdk.shutdown() }
    }

    @Test
    fun crossFeatureOp_koin_sync() {
        KoinSdk.init(testContext, setOf(SdkModule.Encryption.Default, SdkModule.Auth.Default, SdkModule.Storage.Secure, SdkModule.Analytics.Default, SdkModule.Sync.Default), config)
        KoinSdk.get<AuthApi>().login("bench", "pass")
        val sync = KoinSdk.get<SyncApi>()
        benchmarkRule.measureRepeated {
            runBlocking { sync.sync() }
        }
        KoinSdk.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // 4. HYBRID — Koin SDK + Dagger bridge
    //    Uses BenchBridgeComponent (app-specific @Component).
    // ════════════════════════════════════════════════════════

    @Test
    fun hybrid_initCold() = benchmarkRule.measureRepeated {
        KoinSdk.init(testContext, setOf(SdkModule.Encryption.Default, SdkModule.Auth.Default, SdkModule.Storage.Secure, SdkModule.Analytics.Default, SdkModule.Sync.Default), config)
        val bridge = DaggerBenchBridgeComponent.builder().build()
        bridge.encryption(); bridge.hash(); bridge.auth()
        bridge.storage(); bridge.analytics(); bridge.sync()
        runWithMeasurementDisabled { KoinSdk.shutdown() }
    }

    @Test
    fun hybrid_resolveFirst_viaBridge() {
        KoinSdk.init(testContext, setOf(SdkModule.Encryption.Default, SdkModule.Auth.Default, SdkModule.Storage.Secure, SdkModule.Analytics.Default, SdkModule.Sync.Default), config)
        val bridge = DaggerBenchBridgeComponent.builder().build()
        benchmarkRule.measureRepeated {
            bridge.encryption()
        }
        KoinSdk.shutdown()
    }

    @Test
    fun hybrid_resolveCached_viaBridge() {
        KoinSdk.init(testContext, setOf(SdkModule.Encryption.Default, SdkModule.Auth.Default, SdkModule.Storage.Secure, SdkModule.Analytics.Default, SdkModule.Sync.Default), config)
        val bridge = DaggerBenchBridgeComponent.builder().build()
        bridge.encryption() // warm cache
        benchmarkRule.measureRepeated {
            bridge.encryption()
        }
        KoinSdk.shutdown()
    }

    @Test
    fun hybrid_crossFeatureOp_sync() {
        KoinSdk.init(testContext, setOf(SdkModule.Encryption.Default, SdkModule.Auth.Default, SdkModule.Storage.Secure, SdkModule.Analytics.Default, SdkModule.Sync.Default), config)
        val bridge = DaggerBenchBridgeComponent.builder().build()
        bridge.auth().login("bench", "pass")
        val sync = bridge.sync()
        benchmarkRule.measureRepeated {
            runBlocking { sync.sync() }
        }
        KoinSdk.shutdown()
    }

    // ════════════════════════════════════════════════════════
    // 5. STORAGE BACKEND COMPARISON — Fake vs SharedPreferences vs DataStore
    //
    // Tres backends para aislar costes:
    // - FAKE: in-memory HashMap. Mide SOLO el coste del DI framework.
    // - SHARED_PREFS: I/O sincrono. Persistencia real basica.
    // - DATA_STORE: I/O async (coroutines). Persistencia real moderna.
    // ════════════════════════════════════════════════════════

    // ── Fake (DI puro, zero I/O) ──

    @Test
    fun crossFeatureOp_daggerB_fake() {
        DaggerBSdk.init(testContext, config, DaggerBSdk.Feature.entries.toSet(), StorageBackend.FAKE)
        DaggerBSdk.get<AuthApi>().login("bench", "pass")
        val sync = DaggerBSdk.get<SyncApi>()
        benchmarkRule.measureRepeated { runBlocking { sync.sync() } }
        DaggerBSdk.shutdown()
    }

    @Test
    fun crossFeatureOp_daggerC_fake() {
        DaggerCSdk.init(testContext, config, setOf("encryption", "auth", "storage", "analytics", "sync"), StorageBackend.FAKE)
        DaggerCSdk.get<AuthApi>().login("bench", "pass")
        val sync = DaggerCSdk.get<SyncApi>()
        benchmarkRule.measureRepeated { runBlocking { sync.sync() } }
        DaggerCSdk.shutdown()
    }

    @Test
    fun crossFeatureOp_koin_fake() {
        KoinSdk.init(testContext, setOf(SdkModule.Encryption.Default, SdkModule.Auth.Default, SdkModule.Storage.Secure, SdkModule.Analytics.Default, SdkModule.Sync.Default), config, storageBackend = StorageBackend.FAKE)
        KoinSdk.get<AuthApi>().login("bench", "pass")
        val sync = KoinSdk.get<SyncApi>()
        benchmarkRule.measureRepeated { runBlocking { sync.sync() } }
        KoinSdk.shutdown()
    }

    // ── DataStore vs SharedPreferences ──

    @Test
    fun crossFeatureOp_daggerB_datastore() {
        DaggerBSdk.init(testContext, config, DaggerBSdk.Feature.entries.toSet(), StorageBackend.DATA_STORE)
        DaggerBSdk.get<AuthApi>().login("bench", "pass")
        val sync = DaggerBSdk.get<SyncApi>()
        benchmarkRule.measureRepeated {
            runBlocking { sync.sync() }
        }
        DaggerBSdk.shutdown()
    }

    @Test
    fun crossFeatureOp_daggerB_sharedprefs() {
        DaggerBSdk.init(testContext, config, DaggerBSdk.Feature.entries.toSet(), StorageBackend.SHARED_PREFS)
        DaggerBSdk.get<AuthApi>().login("bench", "pass")
        val sync = DaggerBSdk.get<SyncApi>()
        benchmarkRule.measureRepeated {
            runBlocking { sync.sync() }
        }
        DaggerBSdk.shutdown()
    }

    @Test
    fun crossFeatureOp_daggerC_datastore() {
        DaggerCSdk.init(testContext, config, setOf("encryption", "auth", "storage", "analytics", "sync"), StorageBackend.DATA_STORE)
        DaggerCSdk.get<AuthApi>().login("bench", "pass")
        val sync = DaggerCSdk.get<SyncApi>()
        benchmarkRule.measureRepeated {
            runBlocking { sync.sync() }
        }
        DaggerCSdk.shutdown()
    }

    @Test
    fun crossFeatureOp_daggerC_sharedprefs() {
        DaggerCSdk.init(testContext, config, setOf("encryption", "auth", "storage", "analytics", "sync"), StorageBackend.SHARED_PREFS)
        DaggerCSdk.get<AuthApi>().login("bench", "pass")
        val sync = DaggerCSdk.get<SyncApi>()
        benchmarkRule.measureRepeated {
            runBlocking { sync.sync() }
        }
        DaggerCSdk.shutdown()
    }

    @Test
    fun crossFeatureOp_koin_datastore() {
        KoinSdk.init(testContext, setOf(SdkModule.Encryption.Default, SdkModule.Auth.Default, SdkModule.Storage.Secure, SdkModule.Analytics.Default, SdkModule.Sync.Default), config, storageBackend = StorageBackend.DATA_STORE)
        KoinSdk.get<AuthApi>().login("bench", "pass")
        val sync = KoinSdk.get<SyncApi>()
        benchmarkRule.measureRepeated {
            runBlocking { sync.sync() }
        }
        KoinSdk.shutdown()
    }

    @Test
    fun crossFeatureOp_koin_sharedprefs() {
        KoinSdk.init(testContext, setOf(SdkModule.Encryption.Default, SdkModule.Auth.Default, SdkModule.Storage.Secure, SdkModule.Analytics.Default, SdkModule.Sync.Default), config, storageBackend = StorageBackend.SHARED_PREFS)
        KoinSdk.get<AuthApi>().login("bench", "pass")
        val sync = KoinSdk.get<SyncApi>()
        benchmarkRule.measureRepeated {
            runBlocking { sync.sync() }
        }
        KoinSdk.shutdown()
    }
}

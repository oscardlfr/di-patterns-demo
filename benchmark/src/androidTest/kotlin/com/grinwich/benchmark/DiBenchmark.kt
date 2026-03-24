package com.grinwich.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grinwich.benchmark.daggera.*
import com.grinwich.benchmark.daggerb.*
import com.grinwich.benchmark.daggerc.*
import com.grinwich.benchmark.daggerd.*
import com.grinwich.sdk.api.*
import com.grinwich.sdk.common.*
import com.grinwich.sdk.impl.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.dsl.koinApplication
import org.koin.dsl.module

/**
 * Microbenchmarks comparing DI approaches: B (per-feature), C (discovery), Koin.
 * Dagger A (monolithic) included only as baseline — cannot do lazy init.
 *
 * All comparable approaches measure the SAME operations:
 * 1. initCold — create full graph from scratch (6 features)
 * 2. resolveFirst — first resolution of a cached singleton
 * 3. lazyInit_noDeps — add Analytics (0 deps) to a running graph
 * 4. lazyInit_cascade — add Sync (3 deps: Auth→Storage→Encryption) to a running graph
 * 5. crossFeatureOp — Sync.sync() touching Auth+Storage+Encryption
 *
 * Run: ./gradlew :benchmark:connectedReleaseAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class DiBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val config = SdkConfig(debug = false)
    private val noopLogger: SdkLogger = object : SdkLogger {
        override fun d(tag: String, msg: String) {}
        override fun e(tag: String, msg: String, throwable: Throwable?) {}
    }

    // ════════════════════════════════════════════════════════
    // HELPER: build each approach's full graph (for setup)
    // ════════════════════════════════════════════════════════

    private fun buildDaggerB(core: CoreApis): DaggerBFullGraph {
        val enc = DaggerBEncryptionComponent.builder().core(core).build()
        val authCore = BAuthCoreApisImpl(core, enc.encryption())
        val auth = DaggerBAuthComponent.builder().core(authCore).build()
        val storageCore = BStorageCoreApisImpl(core, enc.encryption(), enc.hash())
        val storage = DaggerBStorageComponent.builder().core(storageCore).build()
        val analytics = DaggerBAnalyticsComponent.builder().core(core).build()
        val syncCore = BSyncCoreApisImpl(core, auth.auth(), storage.storage(), enc.encryption())
        val sync = DaggerBSyncComponent.builder().core(syncCore).build()
        return DaggerBFullGraph(enc, auth, storage, analytics, sync)
    }

    private data class DaggerBFullGraph(
        val enc: BEncryptionComponent, val auth: BAuthComponent,
        val storage: BStorageComponent, val analytics: BAnalyticsComponent,
        val sync: BSyncComponent,
    )

    private fun buildDaggerC(core: CoreApis): DaggerCFullGraph {
        val enc = DaggerCEncComponent.builder().core(core).build()
        val auth = DaggerCAuthComponent.builder().enc(enc.encryption()).logger(core.logger).build()
        val storage = DaggerCStorageComponent.builder().enc(enc.encryption()).hash(enc.hash()).logger(core.logger).build()
        val analytics = DaggerCAnalyticsComponent.builder().core(core).build()
        val sync = DaggerCSyncComponent.builder().auth(auth.auth()).storage(storage.storage()).enc(enc.encryption()).logger(core.logger).build()
        return DaggerCFullGraph(enc, auth, storage, analytics, sync)
    }

    private data class DaggerCFullGraph(
        val enc: CEncComponent, val auth: CAuthComponent,
        val storage: CStorageComponent, val analytics: CAnalyticsComponent,
        val sync: CSyncComponent,
    )

    private fun buildKoinFull() = koinApplication {
        modules(module {
            single<SdkConfig> { config }
            single<SdkLogger> { noopLogger }
            single<CoreApis> { CoreApisImpl(get(), get()) }
            single<HashService> { DefaultHashService() }
            single<EncryptionService> { DefaultEncryptionService(get()) }
            single<AuthService> { DefaultAuthService(get(), get()) }
            single<SecureStorageService> { DefaultSecureStorageService(get(), get(), get()) }
            single<AnalyticsService> { DefaultAnalyticsService(get()) }
            single<SyncService> { DefaultSyncService(get(), get(), get(), get()) }
        })
    }

    private fun buildKoinBase() = koinApplication {
        modules(module {
            single<SdkConfig> { config }
            single<SdkLogger> { noopLogger }
            single<CoreApis> { CoreApisImpl(get(), get()) }
            single<HashService> { DefaultHashService() }
            single<EncryptionService> { DefaultEncryptionService(get()) }
        })
    }

    // --- Dagger D helpers ---

    private data class DaggerDFullGraph(
        val core: DCoreComponent, val enc: DEncComponent, val auth: DAuthComponent,
        val storage: DStorageComponent, val analytics: DAnalyticsComponent, val sync: DSyncComponent,
    )

    private fun buildDaggerDCore() = DaggerDCoreComponent.builder()
        .config(config).logger(noopLogger).build()

    private fun buildDaggerD(core: DCoreComponent): DaggerDFullGraph {
        val enc = DaggerDEncComponent.builder().core(core).build()
        val auth = DaggerDAuthComponent.builder().core(core).enc(enc).build()
        val storage = DaggerDStorageComponent.builder().core(core).enc(enc).build()
        val analytics = DaggerDAnalyticsComponent.builder().core(core).build()
        val sync = DaggerDSyncComponent.builder().core(core).enc(enc).auth(auth).storage(storage).build()
        return DaggerDFullGraph(core, enc, auth, storage, analytics, sync)
    }

    // ════════════════════════════════════════════════════════
    // 1. INIT COLD — create the full DI graph (6 features)
    // ════════════════════════════════════════════════════════

    @Test
    fun initCold_daggerA_monolithic() = benchmarkRule.measureRepeated {
        val comp = DaggerMonolithicComponent.builder().config(config).build()
        comp.encryption(); comp.hash(); comp.auth(); comp.storage()
        comp.analytics(); comp.sync()
    }

    @Test
    fun initCold_daggerB_perFeature() = benchmarkRule.measureRepeated {
        val core: CoreApis = CoreApisImpl(config, noopLogger)
        val g = buildDaggerB(core)
        g.enc.encryption(); g.auth.auth(); g.storage.storage(); g.analytics.analytics(); g.sync.sync()
    }

    @Test
    fun initCold_daggerC_discovery() = benchmarkRule.measureRepeated {
        val core: CoreApis = CoreApisImpl(config, noopLogger)
        val g = buildDaggerC(core)
        g.enc.encryption(); g.auth.auth(); g.storage.storage(); g.analytics.analytics(); g.sync.sync()
    }

    @Test
    fun initCold_koin() = benchmarkRule.measureRepeated {
        val app = buildKoinFull()
        val k = app.koin
        k.get<EncryptionService>(); k.get<HashService>(); k.get<AuthService>()
        k.get<SecureStorageService>(); k.get<AnalyticsService>(); k.get<SyncService>()
        runWithTimingDisabled { app.close() }
    }

    // ════════════════════════════════════════════════════════
    // 2. RESOLVE FIRST — first resolution of a cached singleton
    //    (graph already built, service not yet accessed)
    // ════════════════════════════════════════════════════════

    @Test
    fun resolveFirst_daggerB() {
        val core: CoreApis = CoreApisImpl(config, noopLogger)
        val enc = DaggerBEncryptionComponent.builder().core(core).build()
        benchmarkRule.measureRepeated {
            enc.encryption()
        }
    }

    @Test
    fun resolveFirst_daggerC() {
        val core: CoreApis = CoreApisImpl(config, noopLogger)
        val enc = DaggerCEncComponent.builder().core(core).build()
        benchmarkRule.measureRepeated {
            enc.encryption()
        }
    }

    @Test
    fun resolveFirst_koin() {
        val app = buildKoinBase()
        benchmarkRule.measureRepeated {
            app.koin.get<EncryptionService>()
        }
        app.close()
    }

    // ════════════════════════════════════════════════════════
    // 3. LAZY INIT — add a feature to a running graph
    // ════════════════════════════════════════════════════════

    // --- Case 1: Analytics (ZERO cross-feature deps) ---

    @Test
    fun lazyInit_noDeps_daggerB_analytics() {
        val core: CoreApis = CoreApisImpl(config, noopLogger)
        DaggerBEncryptionComponent.builder().core(core).build()
        benchmarkRule.measureRepeated {
            val comp = DaggerBAnalyticsComponent.builder().core(core).build()
            comp.analytics()
        }
    }

    @Test
    fun lazyInit_noDeps_daggerC_analytics() {
        val core: CoreApis = CoreApisImpl(config, noopLogger)
        DaggerCEncComponent.builder().core(core).build()
        benchmarkRule.measureRepeated {
            val comp = DaggerCAnalyticsComponent.builder().core(core).build()
            comp.analytics()
        }
    }

    @Test
    fun lazyInit_noDeps_koin_analytics() {
        val app = buildKoinBase()
        benchmarkRule.measureRepeated {
            val analyticsModule = module {
                single<AnalyticsService> { DefaultAnalyticsService(get()) }
            }
            app.koin.loadModules(listOf(analyticsModule))
            app.koin.get<AnalyticsService>()
            runWithTimingDisabled {
                app.koin.unloadModules(listOf(analyticsModule))
            }
        }
        app.close()
    }

    // --- Case 2: Sync (HEAVY deps — Auth + Storage + Encryption cascade) ---

    @Test
    fun lazyInit_cascade_daggerB_sync() {
        val core: CoreApis = CoreApisImpl(config, noopLogger)
        val enc = DaggerBEncryptionComponent.builder().core(core).build()
        benchmarkRule.measureRepeated {
            val authCore = BAuthCoreApisImpl(core, enc.encryption())
            val auth = DaggerBAuthComponent.builder().core(authCore).build()
            val storageCore = BStorageCoreApisImpl(core, enc.encryption(), enc.hash())
            val storage = DaggerBStorageComponent.builder().core(storageCore).build()
            val syncCore = BSyncCoreApisImpl(core, auth.auth(), storage.storage(), enc.encryption())
            val sync = DaggerBSyncComponent.builder().core(syncCore).build()
            sync.sync()
        }
    }

    @Test
    fun lazyInit_cascade_daggerC_sync() {
        val core: CoreApis = CoreApisImpl(config, noopLogger)
        val enc = DaggerCEncComponent.builder().core(core).build()
        benchmarkRule.measureRepeated {
            val auth = DaggerCAuthComponent.builder().enc(enc.encryption()).logger(core.logger).build()
            val storage = DaggerCStorageComponent.builder().enc(enc.encryption()).hash(enc.hash()).logger(core.logger).build()
            val sync = DaggerCSyncComponent.builder().auth(auth.auth()).storage(storage.storage()).enc(enc.encryption()).logger(core.logger).build()
            sync.sync()
        }
    }

    @Test
    fun lazyInit_cascade_koin_sync() {
        val app = buildKoinBase()
        benchmarkRule.measureRepeated {
            val authMod = module { single<AuthService> { DefaultAuthService(get(), get()) } }
            val storageMod = module { single<SecureStorageService> { DefaultSecureStorageService(get(), get(), get()) } }
            val syncMod = module { single<SyncService> { DefaultSyncService(get(), get(), get(), get()) } }
            app.koin.loadModules(listOf(authMod, storageMod, syncMod))
            app.koin.get<SyncService>()
            runWithTimingDisabled {
                app.koin.unloadModules(listOf(authMod, storageMod, syncMod))
            }
        }
        app.close()
    }

    // ════════════════════════════════════════════════════════
    // 4. CROSS-FEATURE OP — real work crossing Auth+Storage+Encryption
    //    (full graph already built, singletons cached)
    // ════════════════════════════════════════════════════════

    @Test
    fun crossFeatureOp_daggerB_sync() {
        val core: CoreApis = CoreApisImpl(config, noopLogger)
        val g = buildDaggerB(core)
        g.auth.auth().login("bench", "pass")
        val sync = g.sync.sync()  // resolve once outside loop
        benchmarkRule.measureRepeated {
            sync.sync()
        }
    }

    @Test
    fun crossFeatureOp_daggerC_sync() {
        val core: CoreApis = CoreApisImpl(config, noopLogger)
        val g = buildDaggerC(core)
        g.auth.auth().login("bench", "pass")
        val sync = g.sync.sync()  // resolve once outside loop
        benchmarkRule.measureRepeated {
            sync.sync()
        }
    }

    @Test
    fun crossFeatureOp_koin_sync() {
        val app = buildKoinFull()
        val auth = app.koin.get<AuthService>()
        val sync = app.koin.get<SyncService>()
        auth.login("bench", "pass")
        benchmarkRule.measureRepeated {
            sync.sync()  // resolved once outside loop — same as Dagger
        }
        app.close()
    }

    // ════════════════════════════════════════════════════════
    // 5b. DAGGER D — Component Dependencies
    //     Child Components depend on parent via `dependencies = [...]`
    //     Cross-feature deps resolved by Dagger automatically
    // ════════════════════════════════════════════════════════

    @Test
    fun initCold_daggerD_componentDeps() = benchmarkRule.measureRepeated {
        val core = buildDaggerDCore()
        val g = buildDaggerD(core)
        g.enc.encryption(); g.auth.auth(); g.storage.storage(); g.analytics.analytics(); g.sync.sync()
    }

    @Test
    fun resolveFirst_daggerD() {
        val core = buildDaggerDCore()
        val enc = DaggerDEncComponent.builder().core(core).build()
        benchmarkRule.measureRepeated {
            enc.encryption()
        }
    }

    @Test
    fun lazyInit_noDeps_daggerD_analytics() {
        val core = buildDaggerDCore()
        DaggerDEncComponent.builder().core(core).build()
        benchmarkRule.measureRepeated {
            val comp = DaggerDAnalyticsComponent.builder().core(core).build()
            comp.analytics()
        }
    }

    @Test
    fun lazyInit_cascade_daggerD_sync() {
        val core = buildDaggerDCore()
        val enc = DaggerDEncComponent.builder().core(core).build()
        benchmarkRule.measureRepeated {
            // Cascade: Auth + Storage + Sync — all get enc from parent Component
            val auth = DaggerDAuthComponent.builder().core(core).enc(enc).build()
            val storage = DaggerDStorageComponent.builder().core(core).enc(enc).build()
            val sync = DaggerDSyncComponent.builder().core(core).enc(enc).auth(auth).storage(storage).build()
            sync.sync()
        }
    }

    @Test
    fun crossFeatureOp_daggerD_sync() {
        val core = buildDaggerDCore()
        val g = buildDaggerD(core)
        g.auth.auth().login("bench", "pass")
        val sync = g.sync.sync()  // resolve once outside loop
        benchmarkRule.measureRepeated {
            sync.sync()
        }
    }

    // ════════════════════════════════════════════════════════
    // BASELINE: Dagger A (monolithic) — for reference only.
    // Cannot do lazy init. All features always in binary.
    // ════════════════════════════════════════════════════════

    @Test
    fun baseline_daggerA_initCold() = benchmarkRule.measureRepeated {
        val comp = DaggerMonolithicComponent.builder().config(config).build()
        comp.encryption(); comp.hash(); comp.auth(); comp.storage()
        comp.analytics(); comp.sync()
    }

    @Test
    fun baseline_daggerA_resolveFirst() {
        val comp = DaggerMonolithicComponent.builder().config(config).build()
        benchmarkRule.measureRepeated { comp.encryption() }
    }

    @Test
    fun baseline_daggerA_crossFeatureOp() {
        val comp = DaggerMonolithicComponent.builder().config(config).build()
        comp.auth().login("bench", "pass")
        val sync = comp.sync()  // resolve once outside loop
        benchmarkRule.measureRepeated { sync.sync() }
    }

    // ════════════════════════════════════════════════════════
    // 5. HYBRID — Koin SDK + REAL Dagger 2 bridge Component
    //
    // Uses BenchBridgeComponent (real @Component + @Module).
    // @Provides methods call koin.get() — Dagger caches as @Singleton.
    //
    // First call: Dagger calls @Provides → Koin resolves → Dagger caches
    // Subsequent calls: Dagger returns cached instance (~3 ns)
    // ════════════════════════════════════════════════════════

    private fun buildHybridFull(): Pair<org.koin.core.KoinApplication, BenchBridgeComponent> {
        val app = buildKoinFull()
        KoinSdkBenchHelper.init(app)
        val bridge = DaggerBenchBridgeComponent.builder().build()
        return app to bridge
    }

    @Test
    fun hybrid_initCold() = benchmarkRule.measureRepeated {
        // Koin init + Dagger bridge build + force all singletons
        val app = buildKoinFull()
        KoinSdkBenchHelper.init(app)
        val bridge = DaggerBenchBridgeComponent.builder().build()
        bridge.encryption(); bridge.hash(); bridge.auth()
        bridge.storage(); bridge.analytics(); bridge.sync()
        runWithTimingDisabled { KoinSdkBenchHelper.close() }
    }

    @Test
    fun hybrid_resolveFirst_viaBridge() {
        // Full graph + bridge built, first access through Dagger
        val (app, bridge) = buildHybridFull()
        benchmarkRule.measureRepeated {
            bridge.encryption()  // first call: Dagger → @Provides → Koin → cache
        }
        KoinSdkBenchHelper.close()
    }

    @Test
    fun hybrid_resolveCached_viaBridge() {
        // Bridge already resolved — subsequent calls hit Dagger @Singleton cache
        val (app, bridge) = buildHybridFull()
        bridge.encryption()  // warm the Dagger cache
        benchmarkRule.measureRepeated {
            bridge.encryption()  // cached: ~3 ns (same as pure Dagger)
        }
        KoinSdkBenchHelper.close()
    }

    @Test
    fun hybrid_lazyInit_noDeps_analytics() {
        // Base Koin running, add Analytics via loadModules, resolve through Dagger bridge
        val app = buildKoinBase()
        KoinSdkBenchHelper.init(app)
        // Bridge built with full module — but analytics not in Koin yet
        // For lazy: we rebuild bridge after loadModules (real app would use Provider<>)
        benchmarkRule.measureRepeated {
            val mod = module { single<AnalyticsService> { DefaultAnalyticsService(get()) } }
            app.koin.loadModules(listOf(mod))
            app.koin.get<AnalyticsService>()  // resolve via Koin directly (lazy feature)
            runWithTimingDisabled {
                app.koin.unloadModules(listOf(mod))
            }
        }
        KoinSdkBenchHelper.close()
    }

    @Test
    fun hybrid_lazyInit_cascade_sync() {
        val app = buildKoinBase()
        KoinSdkBenchHelper.init(app)
        benchmarkRule.measureRepeated {
            val authMod = module { single<AuthService> { DefaultAuthService(get(), get()) } }
            val storageMod = module { single<SecureStorageService> { DefaultSecureStorageService(get(), get(), get()) } }
            val syncMod = module { single<SyncService> { DefaultSyncService(get(), get(), get(), get()) } }
            app.koin.loadModules(listOf(authMod, storageMod, syncMod))
            app.koin.get<SyncService>()  // lazy feature via Koin
            runWithTimingDisabled {
                app.koin.unloadModules(listOf(authMod, storageMod, syncMod))
            }
        }
        KoinSdkBenchHelper.close()
    }

    @Test
    fun hybrid_crossFeatureOp_sync() {
        // Full graph, Dagger bridge cached, real operation
        val (app, bridge) = buildHybridFull()
        bridge.auth().login("bench", "pass")
        val sync = bridge.sync()  // resolve once (Dagger cached) outside loop
        benchmarkRule.measureRepeated {
            sync.sync()  // only the operation
        }
        KoinSdkBenchHelper.close()
    }
}

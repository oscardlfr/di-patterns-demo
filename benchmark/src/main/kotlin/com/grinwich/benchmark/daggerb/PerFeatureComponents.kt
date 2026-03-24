package com.grinwich.benchmark.daggerb

import com.grinwich.sdk.api.*
import com.grinwich.sdk.common.*
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/** Approach B: Per-Feature — separate Components */

// No-op logger for benchmarks
val benchLogger: SdkLogger = object : SdkLogger {
    override fun d(tag: String, msg: String) {}
    override fun e(tag: String, msg: String, throwable: Throwable?) {}
}

// --- Encryption ---
@Singleton @Component(modules = [BEncMod::class])
interface BEncryptionComponent {
    fun encryption(): EncryptionService
    fun hash(): HashService
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: CoreApis): Builder
        fun build(): BEncryptionComponent
    }
}
@Module class BEncMod {
    @Provides @Singleton fun enc(core: CoreApis): EncryptionService = DefaultEncryptionService(core.logger)
    @Provides @Singleton fun hash(): HashService = DefaultHashService()
}

// --- Auth (needs Encryption) ---
interface BAuthCoreApis : CoreApis { val enc: EncryptionService }
class BAuthCoreApisImpl(private val base: CoreApis, override val enc: EncryptionService) : BAuthCoreApis {
    override val config get() = base.config; override val logger get() = base.logger
}

@Singleton @Component(modules = [BAuthMod::class])
interface BAuthComponent {
    fun auth(): AuthService
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: BAuthCoreApis): Builder
        fun build(): BAuthComponent
    }
}
@Module class BAuthMod {
    @Provides @Singleton fun auth(core: BAuthCoreApis): AuthService = DefaultAuthService(core.enc, core.logger)
}

// --- Storage (needs Encryption + Hash) ---
interface BStorageCoreApis : CoreApis { val enc: EncryptionService; val hash: HashService }
class BStorageCoreApisImpl(private val base: CoreApis, override val enc: EncryptionService, override val hash: HashService) : BStorageCoreApis {
    override val config get() = base.config; override val logger get() = base.logger
}

@Singleton @Component(modules = [BStorageMod::class])
interface BStorageComponent {
    fun storage(): SecureStorageService
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: BStorageCoreApis): Builder
        fun build(): BStorageComponent
    }
}
@Module class BStorageMod {
    @Provides @Singleton fun storage(core: BStorageCoreApis): SecureStorageService = DefaultSecureStorageService(core.enc, core.hash, core.logger)
}

// --- Analytics (no deps) ---
@Singleton @Component(modules = [BAnalyticsMod::class])
interface BAnalyticsComponent {
    fun analytics(): AnalyticsService
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: CoreApis): Builder
        fun build(): BAnalyticsComponent
    }
}
@Module class BAnalyticsMod {
    @Provides @Singleton fun analytics(core: CoreApis): AnalyticsService = DefaultAnalyticsService(core.logger)
}

// --- Sync (needs Auth + Storage + Encryption) ---
interface BSyncCoreApis : CoreApis { val auth: AuthService; val storage: SecureStorageService; val enc: EncryptionService }
class BSyncCoreApisImpl(private val base: CoreApis, override val auth: AuthService, override val storage: SecureStorageService, override val enc: EncryptionService) : BSyncCoreApis {
    override val config get() = base.config; override val logger get() = base.logger
}

@Singleton @Component(modules = [BSyncMod::class])
interface BSyncComponent {
    fun sync(): SyncService
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: BSyncCoreApis): Builder
        fun build(): BSyncComponent
    }
}
@Module class BSyncMod {
    @Provides @Singleton fun sync(core: BSyncCoreApis): SyncService = DefaultSyncService(core.auth, core.storage, core.enc, core.logger)
}

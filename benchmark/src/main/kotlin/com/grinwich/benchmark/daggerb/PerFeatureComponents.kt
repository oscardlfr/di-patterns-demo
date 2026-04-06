package com.grinwich.benchmark.daggerb

import com.grinwich.sdk.api.*
import com.grinwich.sdk.feature.observability.AndroidSdkLogger
import com.grinwich.sdk.common.*
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/** Approach B: Per-Feature — separate Components */

// No-op logger for benchmarks
val benchLogger: SdkLogger = AndroidSdkLogger()

// --- Encryption ---
@Singleton @Component(modules = [BEncMod::class])
interface BEncryptionComponent {
    fun encryption(): EncryptionApi
    fun hash(): HashApi
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: CoreApis): Builder
        fun build(): BEncryptionComponent
    }
}
@Module class BEncMod {
    @Provides @Singleton fun enc(core: CoreApis): EncryptionApi = DefaultEncryptionService(core.logger)
    @Provides @Singleton fun hash(): HashApi = DefaultHashService()
}

// --- Auth (needs Encryption) ---
interface BAuthCoreApis : CoreApis { val enc: EncryptionApi }
class BAuthCoreApisImpl(private val base: CoreApis, override val enc: EncryptionApi) : BAuthCoreApis {
    override val config get() = base.config; override val logger get() = base.logger
}

@Singleton @Component(modules = [BAuthMod::class])
interface BAuthComponent {
    fun auth(): AuthApi
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: BAuthCoreApis): Builder
        fun build(): BAuthComponent
    }
}
@Module class BAuthMod {
    @Provides @Singleton fun auth(core: BAuthCoreApis): AuthApi = DefaultAuthService(core.enc, core.logger)
}

// --- Storage (needs Encryption + Hash) ---
interface BStorageCoreApis : CoreApis { val enc: EncryptionApi; val hash: HashApi }
class BStorageCoreApisImpl(private val base: CoreApis, override val enc: EncryptionApi, override val hash: HashApi) : BStorageCoreApis {
    override val config get() = base.config; override val logger get() = base.logger
}

@Singleton @Component(modules = [BStorageMod::class])
interface BStorageComponent {
    fun storage(): StorageApi
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: BStorageCoreApis): Builder
        fun build(): BStorageComponent
    }
}
@Module class BStorageMod {
    @Provides @Singleton fun storage(core: BStorageCoreApis): StorageApi = DefaultSecureStorageService(core.enc, core.hash, core.logger)
}

// --- Analytics (no deps) ---
@Singleton @Component(modules = [BAnalyticsMod::class])
interface BAnalyticsComponent {
    fun analytics(): AnalyticsApi
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: CoreApis): Builder
        fun build(): BAnalyticsComponent
    }
}
@Module class BAnalyticsMod {
    @Provides @Singleton fun analytics(core: CoreApis): AnalyticsApi = DefaultAnalyticsService(core.logger)
}

// --- Sync (needs Auth + Storage + Encryption) ---
interface BSyncCoreApis : CoreApis { val auth: AuthApi; val storage: StorageApi; val enc: EncryptionApi }
class BSyncCoreApisImpl(private val base: CoreApis, override val auth: AuthApi, override val storage: StorageApi, override val enc: EncryptionApi) : BSyncCoreApis {
    override val config get() = base.config; override val logger get() = base.logger
}

@Singleton @Component(modules = [BSyncMod::class])
interface BSyncComponent {
    fun sync(): SyncApi
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: BSyncCoreApis): Builder
        fun build(): BSyncComponent
    }
}
@Module class BSyncMod {
    @Provides @Singleton fun sync(core: BSyncCoreApis): SyncApi = DefaultSyncService(core.auth, core.storage, core.enc, core.logger)
}

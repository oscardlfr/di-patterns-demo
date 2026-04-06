package com.grinwich.benchmark.daggerc

import com.grinwich.sdk.api.*
import com.grinwich.sdk.feature.observability.AndroidSdkLogger
import com.grinwich.sdk.common.*
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * Approach C benchmark components.
 * ServiceLoader discovery overhead is what we measure — the DaggerComponents
 * themselves are identical to B.
 */

val benchLogger: SdkLogger = AndroidSdkLogger()

// Reuse same per-feature component pattern as B for the DI part.
// The benchmark measures ServiceLoader.load() + init cascade overhead.

@Singleton @Component(modules = [CEncMod::class])
interface CEncComponent {
    fun encryption(): EncryptionApi
    fun hash(): HashApi
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: CoreApis): Builder
        fun build(): CEncComponent
    }
}
@Module class CEncMod {
    @Provides @Singleton fun enc(core: CoreApis): EncryptionApi = DefaultEncryptionService(core.logger)
    @Provides @Singleton fun hash(): HashApi = DefaultHashService()
}

@Singleton @Component(modules = [CAuthMod::class])
interface CAuthComponent {
    fun auth(): AuthApi
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun enc(enc: EncryptionApi): Builder
        @dagger.BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): CAuthComponent
    }
}
@Module class CAuthMod {
    @Provides @Singleton fun auth(enc: EncryptionApi, logger: SdkLogger): AuthApi = DefaultAuthService(enc, logger)
}

@Singleton @Component(modules = [CStorageMod::class])
interface CStorageComponent {
    fun storage(): StorageApi
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun enc(enc: EncryptionApi): Builder
        @dagger.BindsInstance fun hash(hash: HashApi): Builder
        @dagger.BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): CStorageComponent
    }
}
@Module class CStorageMod {
    @Provides @Singleton fun storage(enc: EncryptionApi, hash: HashApi, logger: SdkLogger): StorageApi = DefaultSecureStorageService(enc, hash, logger)
}

@Singleton @Component(modules = [CAnalyticsMod::class])
interface CAnalyticsComponent {
    fun analytics(): AnalyticsApi
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: CoreApis): Builder
        fun build(): CAnalyticsComponent
    }
}
@Module class CAnalyticsMod {
    @Provides @Singleton fun analytics(core: CoreApis): AnalyticsApi = DefaultAnalyticsService(core.logger)
}

@Singleton @Component(modules = [CSyncMod::class])
interface CSyncComponent {
    fun sync(): SyncApi
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun auth(auth: AuthApi): Builder
        @dagger.BindsInstance fun storage(storage: StorageApi): Builder
        @dagger.BindsInstance fun enc(enc: EncryptionApi): Builder
        @dagger.BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): CSyncComponent
    }
}
@Module class CSyncMod {
    @Provides @Singleton fun sync(auth: AuthApi, storage: StorageApi, enc: EncryptionApi, logger: SdkLogger): SyncApi = DefaultSyncService(auth, storage, enc, logger)
}

package com.grinwich.benchmark.daggera

import com.grinwich.sdk.api.*
import com.grinwich.sdk.common.*
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/** Approach A: Monolithic — one @Component, all features */
@Singleton
@Component(modules = [CoreMod::class, EncMod::class, AuthMod::class, StorageMod::class, AnalyticsMod::class, SyncMod::class])
interface MonolithicComponent {
    fun encryption(): EncryptionApi
    fun hash(): HashApi
    fun auth(): AuthApi
    fun storage(): StorageApi
    fun analytics(): AnalyticsApi
    fun sync(): SyncApi

    @Component.Builder interface Builder {
        @dagger.BindsInstance fun config(config: SdkConfig): Builder
        fun build(): MonolithicComponent
    }
}

@Module class CoreMod {
    @Provides @Singleton fun logger(): SdkLogger = object : SdkLogger {
        override fun d(tag: String, msg: String) {}
        override fun e(tag: String, msg: String, throwable: Throwable?) {}
    }
    @Provides @Singleton fun core(config: SdkConfig, logger: SdkLogger): CoreApis = CoreApisImpl(config, logger)
}

@Module class EncMod {
    @Provides @Singleton fun enc(logger: SdkLogger): EncryptionApi = DefaultEncryptionService(logger)
    @Provides @Singleton fun hash(): HashApi = DefaultHashService()
}

@Module class AuthMod {
    @Provides @Singleton fun auth(enc: EncryptionApi, logger: SdkLogger): AuthApi = DefaultAuthService(enc, logger)
}

@Module class StorageMod {
    @Provides @Singleton fun storage(enc: EncryptionApi, hash: HashApi, logger: SdkLogger): StorageApi = DefaultSecureStorageService(enc, hash, logger)
}

@Module class AnalyticsMod {
    @Provides @Singleton fun analytics(logger: SdkLogger): AnalyticsApi = DefaultAnalyticsService(logger)
}

@Module class SyncMod {
    @Provides @Singleton fun sync(auth: AuthApi, storage: StorageApi, enc: EncryptionApi, logger: SdkLogger): SyncApi = DefaultSyncService(auth, storage, enc, logger)
}

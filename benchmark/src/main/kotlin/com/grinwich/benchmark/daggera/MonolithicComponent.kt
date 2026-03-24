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
    fun encryption(): EncryptionService
    fun hash(): HashService
    fun auth(): AuthService
    fun storage(): SecureStorageService
    fun analytics(): AnalyticsService
    fun sync(): SyncService

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
    @Provides @Singleton fun enc(logger: SdkLogger): EncryptionService = DefaultEncryptionService(logger)
    @Provides @Singleton fun hash(): HashService = DefaultHashService()
}

@Module class AuthMod {
    @Provides @Singleton fun auth(enc: EncryptionService, logger: SdkLogger): AuthService = DefaultAuthService(enc, logger)
}

@Module class StorageMod {
    @Provides @Singleton fun storage(enc: EncryptionService, hash: HashService, logger: SdkLogger): SecureStorageService = DefaultSecureStorageService(enc, hash, logger)
}

@Module class AnalyticsMod {
    @Provides @Singleton fun analytics(logger: SdkLogger): AnalyticsService = DefaultAnalyticsService(logger)
}

@Module class SyncMod {
    @Provides @Singleton fun sync(auth: AuthService, storage: SecureStorageService, enc: EncryptionService, logger: SdkLogger): SyncService = DefaultSyncService(auth, storage, enc, logger)
}

package com.grinwich.sample.daggera.di

import com.grinwich.sdk.api.*
import com.grinwich.sdk.common.*
import com.grinwich.sdk.feature.observability.AndroidSdkLogger
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * APPROACH A — MONOLITHIC COMPONENT
 *
 * ONE @Component with ALL modules. Cross-feature deps automatic.
 *
 * getOrInitFeature() LIMITATION:
 * All modules are compiled into the @Component at build time.
 * You cannot add Analytics or Sync later — they're already there or they're not.
 * The only "lazy" thing possible is deferring service instantiation via Provider<>.
 */
@Singleton
@Component(modules = [
    CoreModule::class, EncryptionModule::class, AuthModule::class,
    StorageModule::class, AnalyticsModule::class, SyncModule::class,
])
interface SdkComponent {
    fun encryptionApi(): EncryptionApi
    fun hashApi(): HashApi
    fun authApi(): AuthApi
    fun storageApi(): StorageApi
    fun analyticsApi(): AnalyticsApi
    fun syncApi(): SyncApi
    fun logger(): SdkLogger

    @Component.Builder
    interface Builder {
        @dagger.BindsInstance fun config(config: SdkConfig): Builder
        fun build(): SdkComponent
    }
}

@Module class CoreModule {
    @Provides @Singleton fun logger(): SdkLogger = AndroidSdkLogger()
    @Provides @Singleton fun coreApis(config: SdkConfig, logger: SdkLogger): CoreApis = CoreApisImpl(config, logger)
}

@Module class EncryptionModule {
    @Provides @Singleton fun encryption(logger: SdkLogger): EncryptionApi = DefaultEncryptionService(logger)
    @Provides @Singleton fun hash(): HashApi = DefaultHashService()
}

@Module class AuthModule {
    @Provides @Singleton fun auth(enc: EncryptionApi, logger: SdkLogger): AuthApi = DefaultAuthService(enc, logger)
}

@Module class StorageModule {
    @Provides @Singleton fun storage(enc: EncryptionApi, hash: HashApi, logger: SdkLogger): StorageApi = DefaultSecureStorageService(enc, hash, logger)
}

@Module class AnalyticsModule {
    // Case 1: ZERO deps (only logger from Core)
    @Provides @Singleton fun analytics(logger: SdkLogger): AnalyticsApi = DefaultAnalyticsService(logger)
}

@Module class SyncModule {
    // Case 2: HEAVY cross-feature deps — Auth + Storage + Encryption
    @Provides @Singleton fun sync(
        auth: AuthApi, storage: StorageApi,
        enc: EncryptionApi, logger: SdkLogger,
    ): SyncApi = DefaultSyncService(auth, storage, enc, logger)
}

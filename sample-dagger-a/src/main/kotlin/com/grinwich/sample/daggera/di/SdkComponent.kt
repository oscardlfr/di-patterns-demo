package com.grinwich.sample.daggera.di

import com.grinwich.sdk.api.*
import com.grinwich.sdk.common.*
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
    fun encryptionService(): EncryptionService
    fun hashService(): HashService
    fun authService(): AuthService
    fun storageService(): SecureStorageService
    fun analyticsService(): AnalyticsService
    fun syncService(): SyncService
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
    @Provides @Singleton fun encryption(logger: SdkLogger): EncryptionService = DefaultEncryptionService(logger)
    @Provides @Singleton fun hash(): HashService = DefaultHashService()
}

@Module class AuthModule {
    @Provides @Singleton fun auth(enc: EncryptionService, logger: SdkLogger): AuthService = DefaultAuthService(enc, logger)
}

@Module class StorageModule {
    @Provides @Singleton fun storage(enc: EncryptionService, hash: HashService, logger: SdkLogger): SecureStorageService = DefaultSecureStorageService(enc, hash, logger)
}

@Module class AnalyticsModule {
    // Case 1: ZERO deps (only logger from Core)
    @Provides @Singleton fun analytics(logger: SdkLogger): AnalyticsService = DefaultAnalyticsService(logger)
}

@Module class SyncModule {
    // Case 2: HEAVY cross-feature deps — Auth + Storage + Encryption
    @Provides @Singleton fun sync(
        auth: AuthService, storage: SecureStorageService,
        enc: EncryptionService, logger: SdkLogger,
    ): SyncService = DefaultSyncService(auth, storage, enc, logger)
}

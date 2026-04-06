package com.grinwich.benchmark.daggerd

import com.grinwich.sdk.api.*
import com.grinwich.sdk.common.*
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/** Approach D: Component Dependencies — child depends on parent */

val noopLogger: SdkLogger = object : SdkLogger {
    override fun d(tag: String, msg: String) {}
    override fun e(tag: String, msg: String, throwable: Throwable?) {}
}

// --- Core ---
@Singleton @Component(modules = [DCoreModule::class])
interface DCoreComponent {
    fun logger(): SdkLogger
    fun config(): SdkConfig
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun config(config: SdkConfig): Builder
        @dagger.BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): DCoreComponent
    }
}
@Module class DCoreModule {
    @Provides @Singleton fun core(config: SdkConfig, logger: SdkLogger): CoreApis = CoreApisImpl(config, logger)
}

// --- Encryption (depends on Core) ---
@DEncScope @Component(dependencies = [DCoreComponent::class], modules = [DEncModule::class])
interface DEncComponent {
    fun encryption(): EncryptionApi
    fun hash(): HashApi
    @Component.Builder interface Builder {
        fun core(core: DCoreComponent): Builder
        fun build(): DEncComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class DEncScope
@Module class DEncModule {
    @Provides @DEncScope fun enc(logger: SdkLogger): EncryptionApi = DefaultEncryptionService(logger)
    @Provides @DEncScope fun hash(): HashApi = DefaultHashService()
}

// --- Auth (depends on Core + Encryption) ---
@DAuthScope @Component(dependencies = [DCoreComponent::class, DEncComponent::class], modules = [DAuthModule::class])
interface DAuthComponent {
    fun auth(): AuthApi
    @Component.Builder interface Builder {
        fun core(core: DCoreComponent): Builder
        fun enc(enc: DEncComponent): Builder
        fun build(): DAuthComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class DAuthScope
@Module class DAuthModule {
    @Provides @DAuthScope fun auth(enc: EncryptionApi, logger: SdkLogger): AuthApi = DefaultAuthService(enc, logger)
}

// --- Storage (depends on Core + Encryption) ---
@DStorageScope @Component(dependencies = [DCoreComponent::class, DEncComponent::class], modules = [DStorageModule::class])
interface DStorageComponent {
    fun storage(): StorageApi
    @Component.Builder interface Builder {
        fun core(core: DCoreComponent): Builder
        fun enc(enc: DEncComponent): Builder
        fun build(): DStorageComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class DStorageScope
@Module class DStorageModule {
    @Provides @DStorageScope fun storage(enc: EncryptionApi, hash: HashApi, logger: SdkLogger): StorageApi = DefaultSecureStorageService(enc, hash, logger)
}

// --- Analytics (depends only on Core) ---
@DAnalyticsScope @Component(dependencies = [DCoreComponent::class], modules = [DAnalyticsModule::class])
interface DAnalyticsComponent {
    fun analytics(): AnalyticsApi
    @Component.Builder interface Builder {
        fun core(core: DCoreComponent): Builder
        fun build(): DAnalyticsComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class DAnalyticsScope
@Module class DAnalyticsModule {
    @Provides @DAnalyticsScope fun analytics(logger: SdkLogger): AnalyticsApi = DefaultAnalyticsService(logger)
}

// --- Sync (depends on Core + Encryption + Auth + Storage) ---
@DSyncScope @Component(dependencies = [DCoreComponent::class, DEncComponent::class, DAuthComponent::class, DStorageComponent::class], modules = [DSyncModule::class])
interface DSyncComponent {
    fun sync(): SyncApi
    @Component.Builder interface Builder {
        fun core(core: DCoreComponent): Builder
        fun enc(enc: DEncComponent): Builder
        fun auth(auth: DAuthComponent): Builder
        fun storage(storage: DStorageComponent): Builder
        fun build(): DSyncComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class DSyncScope
@Module class DSyncModule {
    @Provides @DSyncScope fun sync(auth: AuthApi, storage: StorageApi, enc: EncryptionApi, logger: SdkLogger): SyncApi = DefaultSyncService(auth, storage, enc, logger)
}

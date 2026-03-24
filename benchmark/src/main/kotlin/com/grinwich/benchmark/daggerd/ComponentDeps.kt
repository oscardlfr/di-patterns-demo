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
    fun encryption(): EncryptionService
    fun hash(): HashService
    @Component.Builder interface Builder {
        fun core(core: DCoreComponent): Builder
        fun build(): DEncComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class DEncScope
@Module class DEncModule {
    @Provides @DEncScope fun enc(logger: SdkLogger): EncryptionService = DefaultEncryptionService(logger)
    @Provides @DEncScope fun hash(): HashService = DefaultHashService()
}

// --- Auth (depends on Core + Encryption) ---
@DAuthScope @Component(dependencies = [DCoreComponent::class, DEncComponent::class], modules = [DAuthModule::class])
interface DAuthComponent {
    fun auth(): AuthService
    @Component.Builder interface Builder {
        fun core(core: DCoreComponent): Builder
        fun enc(enc: DEncComponent): Builder
        fun build(): DAuthComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class DAuthScope
@Module class DAuthModule {
    @Provides @DAuthScope fun auth(enc: EncryptionService, logger: SdkLogger): AuthService = DefaultAuthService(enc, logger)
}

// --- Storage (depends on Core + Encryption) ---
@DStorageScope @Component(dependencies = [DCoreComponent::class, DEncComponent::class], modules = [DStorageModule::class])
interface DStorageComponent {
    fun storage(): SecureStorageService
    @Component.Builder interface Builder {
        fun core(core: DCoreComponent): Builder
        fun enc(enc: DEncComponent): Builder
        fun build(): DStorageComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class DStorageScope
@Module class DStorageModule {
    @Provides @DStorageScope fun storage(enc: EncryptionService, hash: HashService, logger: SdkLogger): SecureStorageService = DefaultSecureStorageService(enc, hash, logger)
}

// --- Analytics (depends only on Core) ---
@DAnalyticsScope @Component(dependencies = [DCoreComponent::class], modules = [DAnalyticsModule::class])
interface DAnalyticsComponent {
    fun analytics(): AnalyticsService
    @Component.Builder interface Builder {
        fun core(core: DCoreComponent): Builder
        fun build(): DAnalyticsComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class DAnalyticsScope
@Module class DAnalyticsModule {
    @Provides @DAnalyticsScope fun analytics(logger: SdkLogger): AnalyticsService = DefaultAnalyticsService(logger)
}

// --- Sync (depends on Core + Encryption + Auth + Storage) ---
@DSyncScope @Component(dependencies = [DCoreComponent::class, DEncComponent::class, DAuthComponent::class, DStorageComponent::class], modules = [DSyncModule::class])
interface DSyncComponent {
    fun sync(): SyncService
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
    @Provides @DSyncScope fun sync(auth: AuthService, storage: SecureStorageService, enc: EncryptionService, logger: SdkLogger): SyncService = DefaultSyncService(auth, storage, enc, logger)
}

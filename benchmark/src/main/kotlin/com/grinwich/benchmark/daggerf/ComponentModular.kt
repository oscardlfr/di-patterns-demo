package com.grinwich.benchmark.daggerf

import com.grinwich.sdk.api.*
import com.grinwich.sdk.common.*
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * Approach F: Multi-Module Component Dependencies
 *
 * Structurally identical to D at runtime — same component dependencies pattern.
 * The difference is Gradle module layout: CoreComponent in separate :sdk:di-core.
 * Benchmark proves zero overhead from the module separation.
 *
 * CoreComponent here uses pure @BindsInstance (no @Module), matching the
 * actual :sdk:di-core implementation.
 */

// --- Core (lives in :sdk:di-core in production — pure @BindsInstance) ---
@Singleton @Component
interface FCoreComponent {
    fun logger(): SdkLogger
    fun config(): SdkConfig
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun config(config: SdkConfig): Builder
        @dagger.BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): FCoreComponent
    }
}

// --- Encryption (depends on Core) ---
@FEncScope @Component(dependencies = [FCoreComponent::class], modules = [FEncModule::class])
interface FEncComponent {
    fun encryption(): EncryptionService
    fun hash(): HashService
    @Component.Builder interface Builder {
        fun core(core: FCoreComponent): Builder
        fun build(): FEncComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class FEncScope
@Module class FEncModule {
    @Provides @FEncScope fun enc(logger: SdkLogger): EncryptionService = DefaultEncryptionService(logger)
    @Provides @FEncScope fun hash(): HashService = DefaultHashService()
}

// --- Auth (depends on Core + Encryption) ---
@FAuthScope @Component(dependencies = [FCoreComponent::class, FEncComponent::class], modules = [FAuthModule::class])
interface FAuthComponent {
    fun auth(): AuthService
    @Component.Builder interface Builder {
        fun core(core: FCoreComponent): Builder
        fun enc(enc: FEncComponent): Builder
        fun build(): FAuthComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class FAuthScope
@Module class FAuthModule {
    @Provides @FAuthScope fun auth(enc: EncryptionService, logger: SdkLogger): AuthService = DefaultAuthService(enc, logger)
}

// --- Storage (depends on Core + Encryption) ---
@FStorageScope @Component(dependencies = [FCoreComponent::class, FEncComponent::class], modules = [FStorageModule::class])
interface FStorageComponent {
    fun storage(): SecureStorageService
    @Component.Builder interface Builder {
        fun core(core: FCoreComponent): Builder
        fun enc(enc: FEncComponent): Builder
        fun build(): FStorageComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class FStorageScope
@Module class FStorageModule {
    @Provides @FStorageScope fun storage(enc: EncryptionService, hash: HashService, logger: SdkLogger): SecureStorageService =
        DefaultSecureStorageService(enc, hash, logger)
}

// --- Analytics (depends only on Core) ---
@FAnalyticsScope @Component(dependencies = [FCoreComponent::class], modules = [FAnalyticsModule::class])
interface FAnalyticsComponent {
    fun analytics(): AnalyticsService
    @Component.Builder interface Builder {
        fun core(core: FCoreComponent): Builder
        fun build(): FAnalyticsComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class FAnalyticsScope
@Module class FAnalyticsModule {
    @Provides @FAnalyticsScope fun analytics(logger: SdkLogger): AnalyticsService = DefaultAnalyticsService(logger)
}

// --- Sync (depends on Core + Encryption + Auth + Storage) ---
@FSyncScope @Component(dependencies = [FCoreComponent::class, FEncComponent::class, FAuthComponent::class, FStorageComponent::class], modules = [FSyncModule::class])
interface FSyncComponent {
    fun sync(): SyncService
    @Component.Builder interface Builder {
        fun core(core: FCoreComponent): Builder
        fun enc(enc: FEncComponent): Builder
        fun auth(auth: FAuthComponent): Builder
        fun storage(storage: FStorageComponent): Builder
        fun build(): FSyncComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class FSyncScope
@Module class FSyncModule {
    @Provides @FSyncScope fun sync(auth: AuthService, storage: SecureStorageService, enc: EncryptionService, logger: SdkLogger): SyncService =
        DefaultSyncService(auth, storage, enc, logger)
}

package com.grinwich.sdk.modular

import com.grinwich.sdk.api.*
import com.grinwich.sdk.common.*
import com.grinwich.sdk.dicore.CoreComponent
import dagger.Component
import dagger.Module
import dagger.Provides

// ============================================================
// Internal Component hierarchy — consumer never sees these.
//
// Identical to Pattern D, but CoreComponent comes from :sdk:di-core
// (separate Gradle module) instead of being co-located.
//
// In a corporate multi-module setup, each feature below would live
// in its own Gradle module (e.g., :integration:encryption:impl).
// The SDK facade does implementation(:encryption:impl) so the app
// never sees EncComponent — only the facade and :sdk:api interfaces.
// ============================================================

// --- Encryption (depends on Core from :sdk:di-core) ---
@EncScope @Component(dependencies = [CoreComponent::class], modules = [InternalEncModule::class])
internal interface EncComponent {
    fun encryption(): EncryptionApi
    fun hash(): HashApi
    @Component.Builder interface Builder {
        fun core(core: CoreComponent): Builder
        fun build(): EncComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) internal annotation class EncScope
@Module internal class InternalEncModule {
    @Provides @EncScope fun enc(logger: SdkLogger): EncryptionApi = DefaultEncryptionService(logger)
    @Provides @EncScope fun hash(): HashApi = DefaultHashService()
}

// --- Auth (depends on Core + Encryption) ---
@AuthScope @Component(dependencies = [CoreComponent::class, EncComponent::class], modules = [InternalAuthModule::class])
internal interface AuthComponent {
    fun auth(): AuthApi
    @Component.Builder interface Builder {
        fun core(core: CoreComponent): Builder
        fun enc(enc: EncComponent): Builder
        fun build(): AuthComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) internal annotation class AuthScope
@Module internal class InternalAuthModule {
    @Provides @AuthScope fun auth(enc: EncryptionApi, logger: SdkLogger): AuthApi = DefaultAuthService(enc, logger)
}

// --- Storage (depends on Core + Encryption) ---
@StorScope @Component(dependencies = [CoreComponent::class, EncComponent::class], modules = [InternalStorModule::class])
internal interface StorComponent {
    fun storage(): StorageApi
    @Component.Builder interface Builder {
        fun core(core: CoreComponent): Builder
        fun enc(enc: EncComponent): Builder
        fun build(): StorComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) internal annotation class StorScope
@Module internal class InternalStorModule {
    @Provides @StorScope fun storage(enc: EncryptionApi, hash: HashApi, logger: SdkLogger): StorageApi =
        DefaultSecureStorageService(enc, hash, logger)
}

// --- Analytics (depends only on Core) ---
@AnaScope @Component(dependencies = [CoreComponent::class], modules = [InternalAnaModule::class])
internal interface AnaComponent {
    fun analytics(): AnalyticsApi
    @Component.Builder interface Builder {
        fun core(core: CoreComponent): Builder
        fun build(): AnaComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) internal annotation class AnaScope
@Module internal class InternalAnaModule {
    @Provides @AnaScope fun analytics(logger: SdkLogger): AnalyticsApi = DefaultAnalyticsService(logger)
}

// --- Sync (depends on Core + Encryption + Auth + Storage) ---
@SynScope @Component(dependencies = [CoreComponent::class, EncComponent::class, AuthComponent::class, StorComponent::class], modules = [InternalSynModule::class])
internal interface SynComponent {
    fun sync(): SyncApi
    @Component.Builder interface Builder {
        fun core(core: CoreComponent): Builder
        fun enc(enc: EncComponent): Builder
        fun auth(auth: AuthComponent): Builder
        fun storage(storage: StorComponent): Builder
        fun build(): SynComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) internal annotation class SynScope
@Module internal class InternalSynModule {
    @Provides @SynScope fun sync(auth: AuthApi, storage: StorageApi, enc: EncryptionApi, logger: SdkLogger): SyncApi =
        DefaultSyncService(auth, storage, enc, logger)
}

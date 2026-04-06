package com.grinwich.sdk.registry

import com.grinwich.sdk.api.*
import com.grinwich.sdk.common.*
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

// ============================================================
// Internal Dagger components — consumer never sees these.
// Same component-dependency hierarchy as Pattern D, but each
// component extends DiComponent for registry integration.
// ============================================================

// --- Core (root) ---
@Singleton
@Component(modules = [InternalCoreModule::class])
internal interface CoreComponent : DiComponent {
    fun logger(): SdkLogger
    fun config(): SdkConfig

    @Component.Builder interface Builder {
        @dagger.BindsInstance fun config(config: SdkConfig): Builder
        fun build(): CoreComponent
    }
}

@Module
internal class InternalCoreModule {
    @Provides @Singleton fun logger(): SdkLogger = RegistrySdk.foundationLogger
    @Provides @Singleton fun coreApis(config: SdkConfig, logger: SdkLogger): CoreApis = CoreApisImpl(config, logger)
}

// --- Encryption (depends on Core) ---
@EncScope @Component(dependencies = [CoreComponent::class], modules = [InternalEncModule::class])
internal interface EncComponent : DiComponent {
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
internal interface AuthComponent : DiComponent {
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
internal interface StorComponent : DiComponent {
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
internal interface AnaComponent : DiComponent {
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
internal interface SynComponent : DiComponent {
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

// ============================================================
// Feature entries — explicit service bindings, ZERO reflection.
//
// EAGER resolution: services() returns instances directly, not lambdas.
// Dagger scoped components already cache singletons, so calling
// comp.encryption() at registration time is safe and eliminates the
// per-access lambda invoke() overhead.
//
// In a corporate multi-module setup, each module exports its entry
// from its own `di/` package. The SDK facade collects and registers
// them via registerAll() with automatic topological sorting.
// ============================================================

internal fun coreEntry(config: SdkConfig) = FeatureEntry(
    componentClass = CoreComponent::class.java,
    build = { DaggerCoreComponent.builder().config(config).build() },
    services = { comp ->
        mapOf(
            SdkLogger::class.java to comp.logger(),
            SdkConfig::class.java to comp.config(),
        )
    },
)

internal val encryptionEntry = FeatureEntry(
    componentClass = EncComponent::class.java,
    dependencies = setOf(CoreComponent::class.java),
    build = { registry ->
        DaggerEncComponent.builder()
            .core(registry.component(CoreComponent::class.java))
            .build()
    },
    services = { comp ->
        mapOf(
            EncryptionApi::class.java to comp.encryption(),
            HashApi::class.java to comp.hash(),
        )
    },
)

internal val authEntry = FeatureEntry(
    componentClass = AuthComponent::class.java,
    dependencies = setOf(CoreComponent::class.java, EncComponent::class.java),
    build = { registry ->
        DaggerAuthComponent.builder()
            .core(registry.component(CoreComponent::class.java))
            .enc(registry.component(EncComponent::class.java))
            .build()
    },
    services = { comp ->
        mapOf(AuthApi::class.java to comp.auth())
    },
)

internal val storageEntry = FeatureEntry(
    componentClass = StorComponent::class.java,
    dependencies = setOf(CoreComponent::class.java, EncComponent::class.java),
    build = { registry ->
        DaggerStorComponent.builder()
            .core(registry.component(CoreComponent::class.java))
            .enc(registry.component(EncComponent::class.java))
            .build()
    },
    services = { comp ->
        mapOf(StorageApi::class.java to comp.storage())
    },
)

internal val analyticsEntry = FeatureEntry(
    componentClass = AnaComponent::class.java,
    dependencies = setOf(CoreComponent::class.java),
    build = { registry ->
        DaggerAnaComponent.builder()
            .core(registry.component(CoreComponent::class.java))
            .build()
    },
    services = { comp ->
        mapOf(AnalyticsApi::class.java to comp.analytics())
    },
)

internal val syncEntry = FeatureEntry(
    componentClass = SynComponent::class.java,
    dependencies = setOf(CoreComponent::class.java, EncComponent::class.java, AuthComponent::class.java, StorComponent::class.java),
    build = { registry ->
        DaggerSynComponent.builder()
            .core(registry.component(CoreComponent::class.java))
            .enc(registry.component(EncComponent::class.java))
            .auth(registry.component(AuthComponent::class.java))
            .storage(registry.component(StorComponent::class.java))
            .build()
    },
    services = { comp ->
        mapOf(SyncApi::class.java to comp.sync())
    },
)

package com.grinwich.sdk.auto

import com.grinwich.sdk.api.*
import com.grinwich.sdk.common.*
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

// ============================================================
// Internal Dagger components — consumer never sees these.
// Same component-dependency hierarchy as E, but entries use
// AutoFeatureEntry with serviceClasses for auto-init indexing.
// ============================================================

// --- Core (root) ---
@Singleton
@Component
internal interface CoreComponent : DiComponent {
    fun logger(): SdkLogger
    fun config(): SdkConfig
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun config(config: SdkConfig): Builder
        @dagger.BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): CoreComponent
    }
}

// --- Encryption (depends on Core) ---
@EncScope @Component(dependencies = [CoreComponent::class], modules = [InternalEncModule::class])
internal interface EncComponent : DiComponent {
    fun encryption(): EncryptionService
    fun hash(): HashService
    @Component.Builder interface Builder {
        fun core(core: CoreComponent): Builder
        fun build(): EncComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) internal annotation class EncScope
@Module internal class InternalEncModule {
    @Provides @EncScope fun enc(logger: SdkLogger): EncryptionService = DefaultEncryptionService(logger)
    @Provides @EncScope fun hash(): HashService = DefaultHashService()
}

// --- Auth (depends on Core + Encryption) ---
@AuthScope @Component(dependencies = [CoreComponent::class, EncComponent::class], modules = [InternalAuthModule::class])
internal interface AuthComponent : DiComponent {
    fun auth(): AuthService
    @Component.Builder interface Builder {
        fun core(core: CoreComponent): Builder
        fun enc(enc: EncComponent): Builder
        fun build(): AuthComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) internal annotation class AuthScope
@Module internal class InternalAuthModule {
    @Provides @AuthScope fun auth(enc: EncryptionService, logger: SdkLogger): AuthService = DefaultAuthService(enc, logger)
}

// --- Storage (depends on Core + Encryption) ---
@StorScope @Component(dependencies = [CoreComponent::class, EncComponent::class], modules = [InternalStorModule::class])
internal interface StorComponent : DiComponent {
    fun storage(): SecureStorageService
    @Component.Builder interface Builder {
        fun core(core: CoreComponent): Builder
        fun enc(enc: EncComponent): Builder
        fun build(): StorComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) internal annotation class StorScope
@Module internal class InternalStorModule {
    @Provides @StorScope fun storage(enc: EncryptionService, hash: HashService, logger: SdkLogger): SecureStorageService =
        DefaultSecureStorageService(enc, hash, logger)
}

// --- Analytics (depends only on Core) ---
@AnaScope @Component(dependencies = [CoreComponent::class], modules = [InternalAnaModule::class])
internal interface AnaComponent : DiComponent {
    fun analytics(): AnalyticsService
    @Component.Builder interface Builder {
        fun core(core: CoreComponent): Builder
        fun build(): AnaComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) internal annotation class AnaScope
@Module internal class InternalAnaModule {
    @Provides @AnaScope fun analytics(logger: SdkLogger): AnalyticsService = DefaultAnalyticsService(logger)
}

// --- Sync (depends on Core + Encryption + Auth + Storage) ---
@SynScope @Component(dependencies = [CoreComponent::class, EncComponent::class, AuthComponent::class, StorComponent::class], modules = [InternalSynModule::class])
internal interface SynComponent : DiComponent {
    fun sync(): SyncService
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
    @Provides @SynScope fun sync(auth: AuthService, storage: SecureStorageService, enc: EncryptionService, logger: SdkLogger): SyncService =
        DefaultSyncService(auth, storage, enc, logger)
}

// ============================================================
// Feature entries — explicit service bindings + serviceClasses.
//
// KEY EVOLUTION from E: serviceClasses declared upfront so the
// AutoRegistry can index Service → Entry BEFORE building.
// This enables get<T>() → auto-discover → auto-build.
//
// Adding a new module: create entry + add to allEntries(). Done.
// No enum. No when block. No per-component variable. One line.
// ============================================================

internal fun coreEntry(config: SdkConfig, logger: SdkLogger) = AutoFeatureEntry(
    componentClass = CoreComponent::class.java,
    serviceClasses = setOf(SdkLogger::class.java, SdkConfig::class.java),
    build = { DaggerCoreComponent.builder().config(config).logger(logger).build() },
    services = { comp ->
        mapOf(
            SdkLogger::class.java to comp.logger(),
            SdkConfig::class.java to comp.config(),
        )
    },
)

internal val encryptionEntry = AutoFeatureEntry(
    componentClass = EncComponent::class.java,
    dependencies = setOf(CoreComponent::class.java),
    serviceClasses = setOf(EncryptionService::class.java, HashService::class.java),
    build = { reg ->
        DaggerEncComponent.builder()
            .core(reg.component(CoreComponent::class.java))
            .build()
    },
    services = { comp ->
        mapOf(
            EncryptionService::class.java to comp.encryption(),
            HashService::class.java to comp.hash(),
        )
    },
)

internal val authEntry = AutoFeatureEntry(
    componentClass = AuthComponent::class.java,
    dependencies = setOf(CoreComponent::class.java, EncComponent::class.java),
    serviceClasses = setOf(AuthService::class.java),
    build = { reg ->
        DaggerAuthComponent.builder()
            .core(reg.component(CoreComponent::class.java))
            .enc(reg.component(EncComponent::class.java))
            .build()
    },
    services = { comp -> mapOf(AuthService::class.java to comp.auth()) },
)

internal val storageEntry = AutoFeatureEntry(
    componentClass = StorComponent::class.java,
    dependencies = setOf(CoreComponent::class.java, EncComponent::class.java),
    serviceClasses = setOf(SecureStorageService::class.java),
    build = { reg ->
        DaggerStorComponent.builder()
            .core(reg.component(CoreComponent::class.java))
            .enc(reg.component(EncComponent::class.java))
            .build()
    },
    services = { comp -> mapOf(SecureStorageService::class.java to comp.storage()) },
)

internal val analyticsEntry = AutoFeatureEntry(
    componentClass = AnaComponent::class.java,
    dependencies = setOf(CoreComponent::class.java),
    serviceClasses = setOf(AnalyticsService::class.java),
    build = { reg ->
        DaggerAnaComponent.builder()
            .core(reg.component(CoreComponent::class.java))
            .build()
    },
    services = { comp -> mapOf(AnalyticsService::class.java to comp.analytics()) },
)

internal val syncEntry = AutoFeatureEntry(
    componentClass = SynComponent::class.java,
    dependencies = setOf(CoreComponent::class.java, EncComponent::class.java, AuthComponent::class.java, StorComponent::class.java),
    serviceClasses = setOf(SyncService::class.java),
    build = { reg ->
        DaggerSynComponent.builder()
            .core(reg.component(CoreComponent::class.java))
            .enc(reg.component(EncComponent::class.java))
            .auth(reg.component(AuthComponent::class.java))
            .storage(reg.component(StorComponent::class.java))
            .build()
    },
    services = { comp -> mapOf(SyncService::class.java to comp.sync()) },
)

/**
 * All feature entries in this SDK. The facade passes this to registry.installAll().
 * Adding a new module: add one line here. Nothing else changes.
 */
internal fun allEntries(config: SdkConfig, logger: SdkLogger) = listOf(
    coreEntry(config, logger),
    encryptionEntry,
    authEntry,
    storageEntry,
    analyticsEntry,
    syncEntry,
)

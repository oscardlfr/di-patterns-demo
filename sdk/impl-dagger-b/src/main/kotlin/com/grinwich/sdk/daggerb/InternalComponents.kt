package com.grinwich.sdk.daggerb

import com.grinwich.sdk.api.*
import com.grinwich.sdk.common.*
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

// ============================================================
// Internal Components — Per-Feature with CoreApis bridge
// Consumer never sees these.
// ============================================================

// --- Core (plain Kotlin, NOT Dagger) ---

internal class CoreApisHolder(
    val core: CoreApis,
    val logger: SdkLogger,
)

// --- Encryption ---
@Singleton @Component(modules = [IntEncMod::class])
internal interface IntEncComp {
    fun encryption(): EncryptionService
    fun hash(): HashService
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: CoreApis): Builder
        fun build(): IntEncComp
    }
}
@Module internal class IntEncMod {
    @Provides @Singleton fun enc(core: CoreApis): EncryptionService = DefaultEncryptionService(core.logger)
    @Provides @Singleton fun hash(): HashService = DefaultHashService()
}

// --- Auth (needs Encryption via extended CoreApis) ---
internal interface AuthCoreApis : CoreApis { val encryptionService: EncryptionService }
internal class AuthCoreApisImpl(
    private val base: CoreApis,
    override val encryptionService: EncryptionService,
) : AuthCoreApis {
    override val config get() = base.config
    override val logger get() = base.logger
}

@BAuthScope @Component(modules = [IntAuthMod::class])
internal interface IntAuthComp {
    fun auth(): AuthService
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: AuthCoreApis): Builder
        fun build(): IntAuthComp
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) internal annotation class BAuthScope
@Module internal class IntAuthMod {
    @Provides @BAuthScope fun auth(core: AuthCoreApis): AuthService =
        DefaultAuthService(core.encryptionService, core.logger)
}

// --- Storage (needs Encryption + Hash via extended CoreApis) ---
internal interface StorCoreApis : CoreApis {
    val encryptionService: EncryptionService
    val hashService: HashService
}
internal class StorCoreApisImpl(
    private val base: CoreApis,
    override val encryptionService: EncryptionService,
    override val hashService: HashService,
) : StorCoreApis {
    override val config get() = base.config
    override val logger get() = base.logger
}

@BStorScope @Component(modules = [IntStorMod::class])
internal interface IntStorComp {
    fun storage(): SecureStorageService
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: StorCoreApis): Builder
        fun build(): IntStorComp
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) internal annotation class BStorScope
@Module internal class IntStorMod {
    @Provides @BStorScope fun storage(core: StorCoreApis): SecureStorageService =
        DefaultSecureStorageService(core.encryptionService, core.hashService, core.logger)
}

// --- Analytics (only Core) ---
@BAnaScope @Component(modules = [IntAnaMod::class])
internal interface IntAnaComp {
    fun analytics(): AnalyticsService
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: CoreApis): Builder
        fun build(): IntAnaComp
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) internal annotation class BAnaScope
@Module internal class IntAnaMod {
    @Provides @BAnaScope fun analytics(core: CoreApis): AnalyticsService = DefaultAnalyticsService(core.logger)
}

// --- Sync (needs Auth + Storage + Encryption via mega-CoreApis) ---
internal interface SyncCoreApis : CoreApis {
    val authService: AuthService
    val storageService: SecureStorageService
    val encryptionService: EncryptionService
}
internal class SyncCoreApisImpl(
    private val base: CoreApis,
    override val authService: AuthService,
    override val storageService: SecureStorageService,
    override val encryptionService: EncryptionService,
) : SyncCoreApis {
    override val config get() = base.config
    override val logger get() = base.logger
}

@BSynScope @Component(modules = [IntSynMod::class])
internal interface IntSynComp {
    fun sync(): SyncService
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: SyncCoreApis): Builder
        fun build(): IntSynComp
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) internal annotation class BSynScope
@Module internal class IntSynMod {
    @Provides @BSynScope fun sync(core: SyncCoreApis): SyncService =
        DefaultSyncService(core.authService, core.storageService, core.encryptionService, core.logger)
}

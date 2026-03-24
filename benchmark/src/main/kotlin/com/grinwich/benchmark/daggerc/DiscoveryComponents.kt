package com.grinwich.benchmark.daggerc

import com.grinwich.sdk.api.*
import com.grinwich.sdk.common.*
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * Approach C benchmark components.
 * ServiceLoader discovery overhead is what we measure — the DaggerComponents
 * themselves are identical to B.
 */

val benchLogger: SdkLogger = object : SdkLogger {
    override fun d(tag: String, msg: String) {}
    override fun e(tag: String, msg: String, throwable: Throwable?) {}
}

// Reuse same per-feature component pattern as B for the DI part.
// The benchmark measures ServiceLoader.load() + init cascade overhead.

@Singleton @Component(modules = [CEncMod::class])
interface CEncComponent {
    fun encryption(): EncryptionService
    fun hash(): HashService
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: CoreApis): Builder
        fun build(): CEncComponent
    }
}
@Module class CEncMod {
    @Provides @Singleton fun enc(core: CoreApis): EncryptionService = DefaultEncryptionService(core.logger)
    @Provides @Singleton fun hash(): HashService = DefaultHashService()
}

@Singleton @Component(modules = [CAuthMod::class])
interface CAuthComponent {
    fun auth(): AuthService
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun enc(enc: EncryptionService): Builder
        @dagger.BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): CAuthComponent
    }
}
@Module class CAuthMod {
    @Provides @Singleton fun auth(enc: EncryptionService, logger: SdkLogger): AuthService = DefaultAuthService(enc, logger)
}

@Singleton @Component(modules = [CStorageMod::class])
interface CStorageComponent {
    fun storage(): SecureStorageService
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun enc(enc: EncryptionService): Builder
        @dagger.BindsInstance fun hash(hash: HashService): Builder
        @dagger.BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): CStorageComponent
    }
}
@Module class CStorageMod {
    @Provides @Singleton fun storage(enc: EncryptionService, hash: HashService, logger: SdkLogger): SecureStorageService = DefaultSecureStorageService(enc, hash, logger)
}

@Singleton @Component(modules = [CAnalyticsMod::class])
interface CAnalyticsComponent {
    fun analytics(): AnalyticsService
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: CoreApis): Builder
        fun build(): CAnalyticsComponent
    }
}
@Module class CAnalyticsMod {
    @Provides @Singleton fun analytics(core: CoreApis): AnalyticsService = DefaultAnalyticsService(core.logger)
}

@Singleton @Component(modules = [CSyncMod::class])
interface CSyncComponent {
    fun sync(): SyncService
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun auth(auth: AuthService): Builder
        @dagger.BindsInstance fun storage(storage: SecureStorageService): Builder
        @dagger.BindsInstance fun enc(enc: EncryptionService): Builder
        @dagger.BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): CSyncComponent
    }
}
@Module class CSyncMod {
    @Provides @Singleton fun sync(auth: AuthService, storage: SecureStorageService, enc: EncryptionService, logger: SdkLogger): SyncService = DefaultSyncService(auth, storage, enc, logger)
}

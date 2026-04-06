package com.grinwich.sdk.feature.syn

import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.*
import dagger.Component
import dagger.Module
import dagger.Provides

/**
 * SynComponent — heaviest cross-deps in the SDK.
 *
 * dependencies = [CoreProvisions, EncProvisions, AuthProvisions, StorProvisions]:
 *   - All resolved via provision interfaces (contracts)
 *   - This module NEVER imports EncComponent, AuthComponent, or StorComponent
 *   - Dagger sees provision methods on each interface and injects the types
 *
 * In a real project, this file lives in :feature-sync:impl.
 * It compiles independently — only needs di-contracts + sdk:api + sdk:impl-common.
 */
@SynScope
@Component(
    dependencies = [
        CoreProvisions::class,
        EncProvisions::class,
        AuthProvisions::class,
        StorProvisions::class,
    ],
    modules = [SynModule::class],
)
interface SynComponent : SynProvisions {

    override fun sync(): SyncService

    @Component.Builder interface Builder {
        fun core(core: CoreProvisions): Builder
        fun enc(enc: EncProvisions): Builder
        fun auth(auth: AuthProvisions): Builder
        fun storage(storage: StorProvisions): Builder
        fun build(): SynComponent
    }
}

@Module
internal class SynModule {
    @Provides @SynScope
    fun sync(
        auth: AuthService,
        storage: SecureStorageService,
        enc: EncryptionService,
        logger: SdkLogger,
    ): SyncService = DefaultSyncService(auth, storage, enc, logger)
}

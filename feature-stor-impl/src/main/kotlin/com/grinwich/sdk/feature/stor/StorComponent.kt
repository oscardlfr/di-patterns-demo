package com.grinwich.sdk.feature.stor

import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.api.StorageApi
import com.grinwich.sdk.contracts.CoreProvisions
import com.grinwich.sdk.contracts.EncProvisions
import com.grinwich.sdk.contracts.StorProvisions
import com.grinwich.sdk.contracts.StorScope
import dagger.Component
import dagger.Module
import dagger.Provides

/**
 * StorComponent — depends on CoreProvisions + EncProvisions.
 * Same cross-dep pattern as Auth: provision interfaces, not Components.
 */
@StorScope
@Component(
    dependencies = [CoreProvisions::class, EncProvisions::class],
    modules = [StorModule::class],
)
interface StorComponent : StorProvisions {

    override fun storage(): StorageApi

    @Component.Builder interface Builder {
        fun core(core: CoreProvisions): Builder
        fun enc(enc: EncProvisions): Builder
        fun build(): StorComponent
    }
}

@Module
internal class StorModule {
    @Provides @StorScope
    fun storage(enc: EncryptionApi, hash: HashApi, logger: SdkLogger): StorageApi =
        DefaultSecureStorageService(enc, hash, logger)
}

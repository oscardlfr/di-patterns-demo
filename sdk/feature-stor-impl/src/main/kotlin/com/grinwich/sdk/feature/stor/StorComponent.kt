package com.grinwich.sdk.feature.stor

import com.grinwich.sdk.api.EncryptionService
import com.grinwich.sdk.api.HashService
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.api.SecureStorageService
import com.grinwich.sdk.common.DefaultSecureStorageService
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

    override fun storage(): SecureStorageService

    @Component.Builder interface Builder {
        fun core(core: CoreProvisions): Builder
        fun enc(enc: EncProvisions): Builder
        fun build(): StorComponent
    }
}

@Module
internal class StorModule {
    @Provides @StorScope
    fun storage(enc: EncryptionService, hash: HashService, logger: SdkLogger): SecureStorageService =
        DefaultSecureStorageService(enc, hash, logger)
}

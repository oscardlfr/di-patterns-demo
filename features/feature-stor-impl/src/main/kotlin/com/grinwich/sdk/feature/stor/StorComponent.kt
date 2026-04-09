package com.grinwich.sdk.feature.stor

import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.api.StorageApi
import com.grinwich.sdk.contracts.ContextProvisions
import com.grinwich.sdk.contracts.CoreProvisions
import com.grinwich.sdk.contracts.EncProvisions
import com.grinwich.sdk.contracts.StorProvisions
import com.grinwich.sdk.contracts.StorScope
import dagger.BindsInstance
import dagger.Component
import dagger.Module
import dagger.Provides

/** Factory: builds StorProvisions without exposing DaggerStorComponent. */
fun buildStorProvisions(core: CoreProvisions, logger: SdkLogger, enc: EncProvisions, ctx: ContextProvisions): StorProvisions =
    DaggerStorComponent.builder().core(core).logger(logger).enc(enc).ctx(ctx).build()

/**
 * StorComponent — depends on CoreProvisions + EncProvisions + ContextProvisions.
 * Dagger sees ContextProvisions.appContext() and provides android.content.Context.
 */
@StorScope
@Component(
    dependencies = [CoreProvisions::class, EncProvisions::class, ContextProvisions::class],
    modules = [StorModule::class],
)
interface StorComponent : StorProvisions {

    override fun storage(): StorageApi

    @Component.Builder interface Builder {
        fun core(core: CoreProvisions): Builder
        @BindsInstance fun logger(logger: SdkLogger): Builder
        fun enc(enc: EncProvisions): Builder
        fun ctx(ctx: ContextProvisions): Builder
        fun build(): StorComponent
    }
}

@Module
internal class StorModule {
    @Provides @StorScope
    fun storage(ctx: ContextProvisions, encryption: EncryptionApi, hash: HashApi, logger: SdkLogger): StorageApi =
        DefaultSecureStorageService(ctx.appContext(), encryption, hash, logger)
}

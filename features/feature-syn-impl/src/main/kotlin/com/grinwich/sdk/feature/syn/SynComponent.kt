package com.grinwich.sdk.feature.syn

import com.grinwich.sdk.api.AuthApi
import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.api.StorageApi
import com.grinwich.sdk.api.SyncApi
import com.grinwich.sdk.contracts.SynScope
import dagger.Binds
import dagger.BindsInstance
import dagger.Component
import dagger.Module

/**
 * Factory: builds [SyncApi] directly — single-service feature, no Bundle.
 */
fun buildSyncService(
    auth: AuthApi,
    storage: StorageApi,
    encryption: EncryptionApi,
    logger: SdkLogger,
): SyncApi = DaggerSynComponent.builder()
    .auth(auth)
    .storage(storage)
    .encryption(encryption)
    .logger(logger)
    .build()
    .sync()

/**
 * SynComponent — single-service Dagger component.
 *
 * Does not declare `dependencies = [...]`. Everything enters via `@BindsInstance`
 * in the builder: `AuthApi`, `StorageApi`, `EncryptionApi` (cross-feature) and
 * `SdkLogger` (infra). Deepest dependency chain in the SDK.
 */
@SynScope
@Component(modules = [SynModule::class])
internal interface SynComponent {

    fun sync(): SyncApi

    @Component.Builder interface Builder {
        @BindsInstance fun auth(auth: AuthApi): Builder
        @BindsInstance fun storage(storage: StorageApi): Builder
        @BindsInstance fun encryption(encryption: EncryptionApi): Builder
        @BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): SynComponent
    }
}

@Module
internal abstract class SynModule {
    @Binds @SynScope
    abstract fun sync(impl: DefaultSyncService): SyncApi
}

package com.grinwich.sdk.feature.stor

import android.content.Context
import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.api.StorageApi
import com.grinwich.sdk.api.StorageBackend
import com.grinwich.sdk.contracts.StorScope
import dagger.BindsInstance
import dagger.Component
import dagger.Module
import dagger.Provides

/**
 * Factory: builds [StorageApi] directly — the feature is single-service,
 * so it does not need an internal Bundle.
 */
fun buildStorageService(
    context: Context,
    config: SdkConfig,
    encryption: EncryptionApi,
    hash: HashApi,
    logger: SdkLogger,
): StorageApi =
    DaggerStorComponent.builder()
        .context(context)
        .config(config)
        .encryption(encryption)
        .hash(hash)
        .logger(logger)
        .build()
        .storage()

/**
 * StorComponent — single-service Dagger component.
 *
 * Does not declare `dependencies = [...]`. Everything enters via `@BindsInstance`
 * in the builder: `Context` (infra), `SdkConfig` (for backend selection),
 * `EncryptionApi` + `HashApi` (cross-feature) and `SdkLogger` (infra).
 */
@StorScope
@Component(modules = [StorModule::class])
internal interface StorComponent {

    fun storage(): StorageApi

    @Component.Builder interface Builder {
        @BindsInstance fun context(context: Context): Builder
        @BindsInstance fun config(config: SdkConfig): Builder
        @BindsInstance fun encryption(encryption: EncryptionApi): Builder
        @BindsInstance fun hash(hash: HashApi): Builder
        @BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): StorComponent
    }
}

@Module
internal class StorModule {
    @Provides @StorScope
    fun storage(
        context: Context,
        config: SdkConfig,
        encryption: EncryptionApi,
        hash: HashApi,
        logger: SdkLogger,
    ): StorageApi = when (config.storageBackend) {
        StorageBackend.FAKE -> FakeStorageService(encryption, hash, logger)
        StorageBackend.SHARED_PREFS -> SharedPrefsStorageService(context, encryption, hash, logger)
        StorageBackend.DATA_STORE -> DataStoreStorageService(context, encryption, hash, logger)
    }
}

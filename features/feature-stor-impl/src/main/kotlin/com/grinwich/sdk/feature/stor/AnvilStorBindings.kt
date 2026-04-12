package com.grinwich.sdk.feature.stor

import android.content.Context
import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.LazyCreationTracker
import com.grinwich.sdk.contracts.SdkScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Pattern P: kotlin-inject-anvil bindings for Storage feature. */
@ContributesTo(SdkScope::class)
interface AnvilStorBindings {
    @SingleIn(SdkScope::class) @Provides fun provideStorage(
        context: Context,
        encryption: EncryptionApi,
        hash: HashApi,
        logger: SdkLogger,
        storageBackend: StorageBackend,
    ): StorageApi {
        LazyCreationTracker.mark("storage")
        return when (storageBackend) {
            StorageBackend.FAKE -> FakeStorageService(encryption, hash, logger)
            StorageBackend.SHARED_PREFS -> SharedPrefsStorageService(context, encryption, hash, logger)
            StorageBackend.DATA_STORE -> DataStoreStorageService(context, encryption, hash, logger)
        }
    }
}

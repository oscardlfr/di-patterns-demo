package com.grinwich.sdk.feature.stor

import android.content.Context
import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.LazyCreationTracker
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** Pattern O: Metro bindings for Storage feature. */
@ContributesTo(AppScope::class)
interface MetroStorBindings {
    @SingleIn(AppScope::class) @Provides fun provideStorage(
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

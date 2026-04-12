package com.grinwich.sdk.feature.stor

import android.content.Context
import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.LazyCreationTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Pattern Q: Hilt-style Dagger module for Storage feature. */
@Module
@InstallIn(SingletonComponent::class)
object HiltStorModule {
    @Provides @Singleton
    fun provideStorage(
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

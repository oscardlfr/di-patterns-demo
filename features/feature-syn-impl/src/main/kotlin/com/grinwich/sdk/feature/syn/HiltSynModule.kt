package com.grinwich.sdk.feature.syn

import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.LazyCreationTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Pattern Q: Hilt-style Dagger module for Sync feature. */
@Module
@InstallIn(SingletonComponent::class)
object HiltSynModule {
    @Provides @Singleton
    fun provideSync(
        auth: AuthApi,
        storage: StorageApi,
        encryption: EncryptionApi,
        logger: SdkLogger,
    ): SyncApi {
        LazyCreationTracker.mark("sync")
        return DefaultSyncService(auth, storage, encryption, logger)
    }
}

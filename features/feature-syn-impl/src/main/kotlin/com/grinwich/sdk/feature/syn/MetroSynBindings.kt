package com.grinwich.sdk.feature.syn

import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.LazyCreationTracker
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** Pattern O: Metro bindings for Sync feature. */
@ContributesTo(AppScope::class)
interface MetroSynBindings {
    @SingleIn(AppScope::class) @Provides fun provideSync(
        auth: AuthApi,
        storage: StorageApi,
        encryption: EncryptionApi,
        logger: SdkLogger,
    ): SyncApi {
        LazyCreationTracker.mark("sync")
        return DefaultSyncService(auth, storage, encryption, logger)
    }
}

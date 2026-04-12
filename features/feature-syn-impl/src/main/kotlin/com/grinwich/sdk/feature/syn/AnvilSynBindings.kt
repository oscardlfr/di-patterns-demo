package com.grinwich.sdk.feature.syn

import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.LazyCreationTracker
import com.grinwich.sdk.contracts.SdkScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Pattern P: kotlin-inject-anvil bindings for Sync feature. */
@ContributesTo(SdkScope::class)
interface AnvilSynBindings {
    @SingleIn(SdkScope::class) @Provides fun provideSync(
        auth: AuthApi,
        storage: StorageApi,
        encryption: EncryptionApi,
        logger: SdkLogger,
    ): SyncApi {
        LazyCreationTracker.mark("sync")
        return DefaultSyncService(auth, storage, encryption, logger)
    }
}

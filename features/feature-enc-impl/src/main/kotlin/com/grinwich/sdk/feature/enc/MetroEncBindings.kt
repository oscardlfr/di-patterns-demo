package com.grinwich.sdk.feature.enc

import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.LazyCreationTracker
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** Pattern O: Metro bindings for Encryption feature. */
@ContributesTo(AppScope::class)
interface MetroEncBindings {
    @SingleIn(AppScope::class) @Provides fun provideEncryption(logger: SdkLogger): EncryptionApi {
        LazyCreationTracker.mark("encryption")
        return DefaultEncryptionService(logger)
    }
    @SingleIn(AppScope::class) @Provides fun provideHash(): HashApi {
        LazyCreationTracker.mark("encryption")
        return DefaultHashService()
    }
}

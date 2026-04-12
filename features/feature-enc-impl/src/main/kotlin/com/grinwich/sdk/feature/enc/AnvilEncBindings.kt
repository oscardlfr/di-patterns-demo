package com.grinwich.sdk.feature.enc

import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.LazyCreationTracker
import com.grinwich.sdk.contracts.SdkScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Pattern P: kotlin-inject-anvil bindings for Encryption feature. */
@ContributesTo(SdkScope::class)
interface AnvilEncBindings {
    @SingleIn(SdkScope::class) @Provides fun provideEncryption(logger: SdkLogger): EncryptionApi {
        LazyCreationTracker.mark("encryption")
        return DefaultEncryptionService(logger)
    }
    @SingleIn(SdkScope::class) @Provides fun provideHash(): HashApi {
        LazyCreationTracker.mark("encryption")
        return DefaultHashService()
    }
}

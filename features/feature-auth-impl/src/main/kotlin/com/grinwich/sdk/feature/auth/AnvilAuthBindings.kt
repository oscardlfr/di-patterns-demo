package com.grinwich.sdk.feature.auth

import com.grinwich.sdk.api.AuthApi
import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.LazyCreationTracker
import com.grinwich.sdk.contracts.SdkScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Pattern P: kotlin-inject-anvil bindings for Auth feature. */
@ContributesTo(SdkScope::class)
interface AnvilAuthBindings {
    @SingleIn(SdkScope::class) @Provides fun provideAuth(encryption: EncryptionApi, logger: SdkLogger): AuthApi {
        LazyCreationTracker.mark("auth")
        return DefaultAuthService(encryption, logger)
    }
}

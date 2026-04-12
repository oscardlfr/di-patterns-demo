package com.grinwich.sdk.feature.auth

import com.grinwich.sdk.api.AuthApi
import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.LazyCreationTracker
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** Pattern O: Metro bindings for Auth feature. */
@ContributesTo(AppScope::class)
interface MetroAuthBindings {
    @SingleIn(AppScope::class) @Provides fun provideAuth(encryption: EncryptionApi, logger: SdkLogger): AuthApi {
        LazyCreationTracker.mark("auth")
        return DefaultAuthService(encryption, logger)
    }
}

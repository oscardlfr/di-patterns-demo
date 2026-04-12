package com.grinwich.sdk.feature.auth

import com.grinwich.sdk.api.AuthApi
import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.LazyCreationTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Pattern Q: Hilt-style Dagger module for Auth feature. */
@Module
@InstallIn(SingletonComponent::class)
object HiltAuthModule {
    @Provides @Singleton
    fun provideAuth(encryption: EncryptionApi, logger: SdkLogger): AuthApi {
        LazyCreationTracker.mark("auth")
        return DefaultAuthService(encryption, logger)
    }
}

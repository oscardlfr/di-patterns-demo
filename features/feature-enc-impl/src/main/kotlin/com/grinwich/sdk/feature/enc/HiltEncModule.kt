package com.grinwich.sdk.feature.enc

import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.LazyCreationTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Pattern Q: Hilt-style Dagger module for Encryption feature.
 *
 * @InstallIn(SingletonComponent) marks this module for automatic inclusion
 * by Hilt in a real app. For benchmarking, the wiring module includes it
 * explicitly in a @Component(modules=[...]).
 */
@Module
@InstallIn(SingletonComponent::class)
object HiltEncModule {
    @Provides @Singleton
    fun provideEncryption(logger: SdkLogger): EncryptionApi {
        LazyCreationTracker.mark("encryption")
        return DefaultEncryptionService(logger)
    }

    @Provides @Singleton
    fun provideHash(): HashApi {
        LazyCreationTracker.mark("encryption")
        return DefaultHashService()
    }
}

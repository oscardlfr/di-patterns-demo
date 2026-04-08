package com.grinwich.sample.multimodule.di

import com.grinwich.sdk.api.*
import com.grinwich.sdk.wiring.h.MultiModuleSdkH
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * Bridges SDK services into Dagger's object graph.
 *
 * Each @Provides resolves the service from the SDK on first call.
 * Dagger caches the result as @Singleton — subsequent injections
 * return the same instance without calling MultiModuleSdkH.get() again.
 *
 * This is the pattern for apps that already use Dagger 2 and want
 * to integrate the multi-module SDK without changing their DI setup.
 */
@Module
class SdkBridgeModule {

    @Provides @Singleton
    fun encryption(): EncryptionApi = MultiModuleSdkH.get()

    @Provides @Singleton
    fun hash(): HashApi = MultiModuleSdkH.get()

    @Provides @Singleton
    fun auth(): AuthApi = MultiModuleSdkH.get()

    @Provides @Singleton
    fun storage(): StorageApi = MultiModuleSdkH.get()

    @Provides @Singleton
    fun analytics(): AnalyticsApi = MultiModuleSdkH.get()

    @Provides @Singleton
    fun sync(): SyncApi = MultiModuleSdkH.get()
}

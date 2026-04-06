package com.grinwich.benchmark

import com.grinwich.sdk.api.*
import com.grinwich.sdk.impl.KoinSdk
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * Real Dagger 2 bridge Component for hybrid benchmarks.
 *
 * Identical to what sample-hybrid uses — @Provides resolves from Koin.
 * This is an app-specific Component (each app creates its own bridge).
 */
@Singleton
@Component(modules = [BenchBridgeModule::class])
interface BenchBridgeComponent {
    fun encryption(): EncryptionApi
    fun hash(): HashApi
    fun auth(): AuthApi
    fun storage(): StorageApi
    fun analytics(): AnalyticsApi
    fun sync(): SyncApi

    @Component.Builder
    interface Builder {
        fun build(): BenchBridgeComponent
    }
}

@Module
class BenchBridgeModule {
    @Provides @Singleton
    fun encryption(): EncryptionApi = KoinSdk.koin.get()

    @Provides @Singleton
    fun hash(): HashApi = KoinSdk.koin.get()

    @Provides @Singleton
    fun auth(): AuthApi = KoinSdk.koin.get()

    @Provides @Singleton
    fun storage(): StorageApi = KoinSdk.koin.get()

    @Provides @Singleton
    fun analytics(): AnalyticsApi = KoinSdk.koin.get()

    @Provides @Singleton
    fun sync(): SyncApi = KoinSdk.koin.get()
}

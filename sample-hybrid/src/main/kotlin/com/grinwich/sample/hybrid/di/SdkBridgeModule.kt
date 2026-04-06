package com.grinwich.sample.hybrid.di

import com.grinwich.sdk.api.*
import com.grinwich.sdk.impl.KoinSdk
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * THE BRIDGE — Dagger 2 Component that pulls services from Koin SDK.
 *
 * How it works:
 * 1. App creates Dagger Component (after KoinSdk.init())
 * 2. Each @Provides calls KoinSdk.get<T>() — Koin resolves from its graph
 * 3. Dagger caches the result as @Singleton
 * 4. App code injects via Dagger — zero Koin knowledge
 *
 * LAZY INIT NOTE: Services loaded lazily via getOrInitModule() are available
 * through KoinSdk.get() immediately after initialization. But Dagger @Singleton
 * caches the FIRST resolution. For lazy features, access via KoinSdk.get()
 * directly, or use Provider<> in the Component.
 */
@Singleton
@Component(modules = [SdkBridgeModule::class])
interface SdkBridgeComponent {
    fun encryption(): EncryptionApi
    fun hash(): HashApi

    // Lazy features: use KoinSdk.get() at call-time, NOT through Component.
    // These may not exist when the Component is created.
    //
    // fun auth(): AuthApi          // ← would crash if Auth not init'd
    // fun storage(): StorageApi
    // fun analytics(): AnalyticsApi
    // fun sync(): SyncApi
    //
    // ⚠️ For lazy features in hybrid, access KoinSdk directly.

    @Component.Builder
    interface Builder {
        fun build(): SdkBridgeComponent
    }
}

@Module
class SdkBridgeModule {

    // --- Available at startup (init'd in Application.onCreate) ---

    @Provides @Singleton
    fun provideEncryptionApi(): EncryptionApi = KoinSdk.get()

    @Provides @Singleton
    fun provideHashApi(): HashApi = KoinSdk.get()
}

package com.grinwich.benchmark

import com.grinwich.sdk.api.*
import com.grinwich.sdk.common.*
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * Real Dagger 2 bridge Component for hybrid benchmarks.
 * Identical to what sample-hybrid uses — @Provides resolves from Koin.
 */
@Singleton
@Component(modules = [BenchBridgeModule::class])
interface BenchBridgeComponent {
    fun encryption(): EncryptionService
    fun hash(): HashService
    fun auth(): AuthService
    fun storage(): SecureStorageService
    fun analytics(): AnalyticsService
    fun sync(): SyncService

    @Component.Builder
    interface Builder {
        fun build(): BenchBridgeComponent
    }
}

@Module
class BenchBridgeModule {
    // Each @Provides pulls from Koin — exactly what the real bridge does.
    // Dagger generates a factory that calls these once (@Singleton) and caches.

    @Provides @Singleton
    fun encryption(koin: org.koin.core.Koin): EncryptionService = koin.get()

    @Provides @Singleton
    fun hash(koin: org.koin.core.Koin): HashService = koin.get()

    @Provides @Singleton
    fun auth(koin: org.koin.core.Koin): AuthService = koin.get()

    @Provides @Singleton
    fun storage(koin: org.koin.core.Koin): SecureStorageService = koin.get()

    @Provides @Singleton
    fun analytics(koin: org.koin.core.Koin): AnalyticsService = koin.get()

    @Provides @Singleton
    fun sync(koin: org.koin.core.Koin): SyncService = koin.get()

    @Provides @Singleton
    fun koin(): org.koin.core.Koin = KoinSdkBenchHelper.koin
}

/**
 * Thin wrapper to hold the Koin instance for benchmark bridge.
 * In real app this would be KoinSdk.koin.
 */
object KoinSdkBenchHelper {
    private var _app: org.koin.core.KoinApplication? = null

    val koin: org.koin.core.Koin get() = _app!!.koin

    fun init(app: org.koin.core.KoinApplication) { _app = app }
    fun close() { _app?.close(); _app = null }
}

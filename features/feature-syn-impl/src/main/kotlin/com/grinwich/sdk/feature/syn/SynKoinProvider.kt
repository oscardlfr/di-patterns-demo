package com.grinwich.sdk.feature.syn

import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.koin.CreationTracker
import com.grinwich.sdk.contracts.koin.KoinFeatureProvider
import org.koin.dsl.module

class SynKoinProvider : KoinFeatureProvider("sync") {
    override val services = setOf(SyncApi::class.java)
    override val requiredServices = setOf(
        AuthApi::class.java,
        StorageApi::class.java,
        EncryptionApi::class.java,
    )
    override fun module() = module {
        single<SyncApi> { get<CreationTracker>().mark("sync"); DefaultSyncService(get(), get(), get(), get()) }
    }
}

package com.grinwich.sdk.feature.stor

import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.koin.CreationTracker
import com.grinwich.sdk.contracts.koin.KoinFeatureProvider
import dev.whyoleg.sweetspi.ServiceProvider
import org.koin.dsl.module

/** Pattern N: sweet-spi KMP-compatible discovery for Storage feature. */
@ServiceProvider
object StorSweetSpiProvider : KoinFeatureProvider("storage") {
    override val services = setOf(StorageApi::class.java)
    override val requiredServices = setOf(EncryptionApi::class.java, HashApi::class.java)
    override fun module() = module {
        single<StorageApi> {
            get<CreationTracker>().mark("storage")
            when (get<StorageBackend>()) {
                StorageBackend.FAKE -> FakeStorageService(get(), get(), get())
                StorageBackend.SHARED_PREFS -> SharedPrefsStorageService(get(), get(), get(), get())
                StorageBackend.DATA_STORE -> DataStoreStorageService(get(), get(), get(), get())
            }
        }
    }
}

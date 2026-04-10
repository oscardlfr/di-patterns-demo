package com.grinwich.sdk.feature.stor

import com.grinwich.sdk.api.StorageApi
import com.grinwich.sdk.api.StorageBackend
import com.grinwich.sdk.contracts.*

class StorPureProvider : PureFeatureProvider<StorProvisions>(StorProvisions::class.java) {
    override val services: Map<Class<*>, (StorProvisions) -> Any> = mapOf(
        StorageApi::class.java to StorProvisions::storage,
    )
    override fun build(resolver: Resolver): StorProvisions {
        val config = resolver.provision(CoreProvisions::class.java).config()
        val ctx = resolver.provision(ContextProvisions::class.java).appContext()
        val enc = resolver.provision(EncProvisions::class.java)
        val logger = resolver.logger
        val storage = when (config.storageBackend) {
            StorageBackend.FAKE -> FakeStorageService(enc.encryption(), enc.hash(), logger)
            StorageBackend.SHARED_PREFS -> SharedPrefsStorageService(ctx, enc.encryption(), enc.hash(), logger)
            StorageBackend.DATA_STORE -> DataStoreStorageService(ctx, enc.encryption(), enc.hash(), logger)
        }
        return object : StorProvisions {
            override fun storage() = storage
        }
    }
}

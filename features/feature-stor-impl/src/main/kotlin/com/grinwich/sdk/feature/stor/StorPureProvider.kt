package com.grinwich.sdk.feature.stor

import android.content.Context
import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi
import com.grinwich.sdk.api.StorageApi
import com.grinwich.sdk.api.StorageBackend
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver

/** Storage provider with flavor [Flavor.PURE]. Consumed by pattern I. */
class StorPureProvider : FeatureProvider() {
    override val flavor = Flavor.PURE
    override val services = setOf(StorageApi::class.java)

    override fun build(resolver: Resolver): Map<Class<*>, Any> {
        val context = resolver.get(Context::class.java)
        val encryption = resolver.get(EncryptionApi::class.java)
        val hash = resolver.get(HashApi::class.java)
        val logger = resolver.get(SdkLogger::class.java)
        val storage = when (resolver.get(SdkConfig::class.java).storageBackend) {
            StorageBackend.FAKE -> FakeStorageService(encryption, hash, logger)
            StorageBackend.SHARED_PREFS -> SharedPrefsStorageService(context, encryption, hash, logger)
            StorageBackend.DATA_STORE -> DataStoreStorageService(context, encryption, hash, logger)
        }
        return mapOf(StorageApi::class.java to storage)
    }
}

package com.grinwich.sdk.feature.stor

import android.content.Context
import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi
import com.grinwich.sdk.api.StorageApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver

/** Storage provider with flavor [Flavor.DAGGER]. Consumed by pattern H. */
class StorProvider : FeatureProvider() {
    override val flavor = Flavor.DAGGER
    override val services = setOf(StorageApi::class.java)

    override fun build(resolver: Resolver): Map<Class<*>, Any> {
        val storage = buildStorageService(
            context = resolver.get(Context::class.java),
            config = resolver.get(SdkConfig::class.java),
            encryption = resolver.get(EncryptionApi::class.java),
            hash = resolver.get(HashApi::class.java),
            logger = resolver.get(SdkLogger::class.java),
        )
        return mapOf(StorageApi::class.java to storage)
    }
}

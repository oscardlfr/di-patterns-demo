package com.grinwich.sdk.feature.syn

import com.grinwich.sdk.api.AuthApi
import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.StorageApi
import com.grinwich.sdk.api.SyncApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver

/** Sync provider with flavor [Flavor.DAGGER]. Consumed by pattern H. */
class SynProvider : FeatureProvider() {
    override val flavor = Flavor.DAGGER
    override val services = setOf(SyncApi::class.java)

    override fun build(resolver: Resolver): Map<Class<*>, Any> {
        val sync = buildSyncService(
            auth = resolver.get(AuthApi::class.java),
            storage = resolver.get(StorageApi::class.java),
            encryption = resolver.get(EncryptionApi::class.java),
            logger = resolver.get(SdkLogger::class.java),
        )
        return mapOf(SyncApi::class.java to sync)
    }
}

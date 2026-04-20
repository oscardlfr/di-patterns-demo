package com.grinwich.sdk.feature.syn

import com.grinwich.sdk.api.AuthApi
import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.api.StorageApi
import com.grinwich.sdk.api.SyncApi
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

@Component
internal abstract class KISynComponent(
    @get:Provides val auth: AuthApi,
    @get:Provides val storage: StorageApi,
    @get:Provides val encryption: EncryptionApi,
    @get:Provides val logger: SdkLogger,
) {
    abstract val sync: SyncApi

    @Provides fun syncApi(): SyncApi = DefaultSyncService(auth, storage, encryption, logger)
}

/** Sync provider with flavor [Flavor.KI]. Consumed by pattern J. */
class SynKIProvider : FeatureProvider() {
    override val flavor = Flavor.KI
    override val services = setOf(SyncApi::class.java)

    override fun build(resolver: Resolver): Map<Class<*>, Any> {
        val component = KISynComponent::class.create(
            auth = resolver.get(AuthApi::class.java),
            storage = resolver.get(StorageApi::class.java),
            encryption = resolver.get(EncryptionApi::class.java),
            logger = resolver.get(SdkLogger::class.java),
        )
        return mapOf(SyncApi::class.java to component.sync)
    }
}

package com.grinwich.sdk.feature.stor

import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.api.StorageApi
import com.grinwich.sdk.contracts.*
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

@Component
abstract class KIStorComponent(
    @get:Provides val encryption: EncryptionApi,
    @get:Provides val hash: HashApi,
    @get:Provides val logger: SdkLogger,
) {
    abstract val storage: StorageApi

    @Provides fun storageApi(): StorageApi = DefaultSecureStorageService(encryption, hash, logger)
}

class StorKIProvider : KIFeatureProvider<StorProvisions>(StorProvisions::class.java) {
    override val services: Map<Class<*>, (StorProvisions) -> Any> = mapOf(
        StorageApi::class.java to StorProvisions::storage,
    )
    override fun build(resolver: Resolver): StorProvisions {
        val enc = resolver.provision(EncProvisions::class.java)
        val component = KIStorComponent::class.create(
            encryption = enc.encryption(),
            hash = enc.hash(),
            logger = resolver.logger,
        )
        val storage = component.storage
        return object : StorProvisions {
            override fun storage() = storage
        }
    }
}

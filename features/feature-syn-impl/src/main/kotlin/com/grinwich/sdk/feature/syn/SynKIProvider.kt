package com.grinwich.sdk.feature.syn

import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.*
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

@Component
abstract class KISynComponent(
    @get:Provides val auth: AuthApi,
    @get:Provides val storage: StorageApi,
    @get:Provides val encryption: EncryptionApi,
    @get:Provides val logger: SdkLogger,
) {
    abstract val sync: SyncApi

    @Provides fun syncApi(): SyncApi = DefaultSyncService(auth, storage, encryption, logger)
}

class SynKIProvider : KIFeatureProvider<SynProvisions>(SynProvisions::class.java) {
    override val services: Map<Class<*>, (SynProvisions) -> Any> = mapOf(
        SyncApi::class.java to SynProvisions::sync,
    )
    override fun build(resolver: Resolver): SynProvisions {
        val core = resolver.provision(CoreProvisions::class.java)
        val enc = resolver.provision(EncProvisions::class.java)
        val auth = resolver.provision(AuthProvisions::class.java)
        val stor = resolver.provision(StorProvisions::class.java)
        val component = KISynComponent::class.create(
            auth = auth.auth(),
            storage = stor.storage(),
            encryption = enc.encryption(),
            logger = resolver.logger,
        )
        val sync = component.sync
        return object : SynProvisions {
            override fun sync() = sync
        }
    }
}

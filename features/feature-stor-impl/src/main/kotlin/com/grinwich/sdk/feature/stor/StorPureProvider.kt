package com.grinwich.sdk.feature.stor

import com.grinwich.sdk.api.StorageApi
import com.grinwich.sdk.contracts.*

class StorPureProvider : PureFeatureProvider<StorProvisions>(StorProvisions::class.java) {
    override val services: Map<Class<*>, (StorProvisions) -> Any> = mapOf(
        StorageApi::class.java to StorProvisions::storage,
    )
    override fun build(resolver: Resolver): StorProvisions {
        val ctx = resolver.provision(ContextProvisions::class.java).appContext()
        val enc = resolver.provision(EncProvisions::class.java)
        val logger = resolver.logger
        val stor = DefaultSecureStorageService(ctx, enc.encryption(), enc.hash(), logger)
        return object : StorProvisions {
            override fun storage() = stor
        }
    }
}

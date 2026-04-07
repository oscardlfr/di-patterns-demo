package com.grinwich.sdk.feature.stor

import com.grinwich.sdk.api.StorageApi
import com.grinwich.sdk.contracts.*

class StorProvider : FeatureProvider<StorProvisions>(StorProvisions::class.java) {

    override val services: Map<Class<*>, (StorProvisions) -> Any> = mapOf(
        StorageApi::class.java to StorProvisions::storage,
    )

    override fun build(resolver: Resolver): StorProvisions =
        buildStorProvisions(resolver.provision(CoreProvisions::class.java), resolver.logger, resolver.provision(EncProvisions::class.java))
}

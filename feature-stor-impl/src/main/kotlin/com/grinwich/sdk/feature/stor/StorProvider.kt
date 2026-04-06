package com.grinwich.sdk.feature.stor

import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.api.StorageApi
import com.grinwich.sdk.contracts.CoreProvisions
import com.grinwich.sdk.contracts.EncProvisions
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Resolver
import com.grinwich.sdk.contracts.StorProvisions

class StorProvider : FeatureProvider<StorProvisions>(StorProvisions::class.java) {

    override val services: Map<Class<*>, (StorProvisions) -> Any> = mapOf(
        StorageApi::class.java to StorProvisions::storage,
    )

    override fun build(resolver: Resolver, logger: SdkLogger): StorProvisions =
        buildStorProvisions(resolver.provision(CoreProvisions::class.java), logger, resolver.provision(EncProvisions::class.java))
}

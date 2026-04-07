package com.grinwich.sdk.feature.syn

import com.grinwich.sdk.api.SyncApi
import com.grinwich.sdk.contracts.*

class SynProvider : FeatureProvider<SynProvisions>(SynProvisions::class.java) {

    override val services: Map<Class<*>, (SynProvisions) -> Any> = mapOf(
        SyncApi::class.java to SynProvisions::sync,
    )

    override fun build(resolver: Resolver): SynProvisions =
        buildSynProvisions(
            resolver.provision(CoreProvisions::class.java),
            resolver.logger,
            resolver.provision(EncProvisions::class.java),
            resolver.provision(AuthProvisions::class.java),
            resolver.provision(StorProvisions::class.java),
        )
}

package com.grinwich.sdk.feature.syn

import com.grinwich.sdk.api.SyncApi
import com.grinwich.sdk.contracts.*

class SynPureProvider : PureFeatureProvider<SynProvisions>(SynProvisions::class.java) {
    override val services: Map<Class<*>, (SynProvisions) -> Any> = mapOf(
        SyncApi::class.java to SynProvisions::sync,
    )
    override fun build(resolver: Resolver): SynProvisions {
        val core = resolver.provision(CoreProvisions::class.java)
        val logger = resolver.logger
        val enc = resolver.provision(EncProvisions::class.java)
        val auth = resolver.provision(AuthProvisions::class.java)
        val stor = resolver.provision(StorProvisions::class.java)
        val syn = DefaultSyncService(auth.auth(), stor.storage(), enc.encryption(), logger)
        return object : SynProvisions {
            override fun sync() = syn
        }
    }
}

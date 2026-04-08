package com.grinwich.sdk.feature.core

import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.contracts.CoreProvisions
import com.grinwich.sdk.contracts.PureFeatureProvider
import com.grinwich.sdk.contracts.Resolver

class CorePureProvider : PureFeatureProvider<CoreProvisions>(CoreProvisions::class.java) {
    override val services: Map<Class<*>, (CoreProvisions) -> Any> = mapOf(
        SdkConfig::class.java to CoreProvisions::config,
    )
    override fun build(resolver: Resolver): CoreProvisions {
        val config = resolver.config
        return object : CoreProvisions {
            override fun config() = config
        }
    }
}

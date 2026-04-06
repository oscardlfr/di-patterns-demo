package com.grinwich.sdk.feature.core

import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.CoreProvisions
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Resolver

class CoreProvider(private val config: SdkConfig) : FeatureProvider<CoreProvisions>(CoreProvisions::class.java) {

    override val services: Map<Class<*>, (CoreProvisions) -> Any> = mapOf(
        SdkConfig::class.java to CoreProvisions::config,
    )

    override fun build(resolver: Resolver, logger: SdkLogger): CoreProvisions =
        buildCoreProvisions(config)
}

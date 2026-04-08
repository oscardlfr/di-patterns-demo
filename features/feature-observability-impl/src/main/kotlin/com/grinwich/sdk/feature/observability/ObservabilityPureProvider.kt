package com.grinwich.sdk.feature.observability

import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.ObservabilityProvisions
import com.grinwich.sdk.contracts.PureFeatureProvider
import com.grinwich.sdk.contracts.Resolver

class ObservabilityPureProvider : PureFeatureProvider<ObservabilityProvisions>(ObservabilityProvisions::class.java) {
    override val services: Map<Class<*>, (ObservabilityProvisions) -> Any> = mapOf(
        SdkLogger::class.java to ObservabilityProvisions::logger,
    )
    override fun build(resolver: Resolver): ObservabilityProvisions {
        val logger = AndroidSdkLogger()
        return object : ObservabilityProvisions {
            override fun logger() = logger
        }
    }
}

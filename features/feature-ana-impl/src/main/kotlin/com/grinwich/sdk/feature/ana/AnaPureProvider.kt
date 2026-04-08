package com.grinwich.sdk.feature.ana

import com.grinwich.sdk.api.AnalyticsApi
import com.grinwich.sdk.contracts.*

class AnaPureProvider : PureFeatureProvider<AnaProvisions>(AnaProvisions::class.java) {
    override val services: Map<Class<*>, (AnaProvisions) -> Any> = mapOf(
        AnalyticsApi::class.java to AnaProvisions::analytics,
    )
    override fun build(resolver: Resolver): AnaProvisions {
        val logger = resolver.logger
        val ana = DefaultAnalyticsService(logger)
        return object : AnaProvisions {
            override fun analytics() = ana
        }
    }
}

package com.grinwich.sdk.feature.ana

import com.grinwich.sdk.api.AnalyticsApi
import com.grinwich.sdk.contracts.*

class AnaProvider : FeatureProvider<AnaProvisions>(AnaProvisions::class.java) {

    override val services: Map<Class<*>, (AnaProvisions) -> Any> = mapOf(
        AnalyticsApi::class.java to AnaProvisions::analytics,
    )

    override fun build(resolver: Resolver): AnaProvisions =
        buildAnaProvisions(resolver.provision(CoreProvisions::class.java), resolver.logger)
}

package com.grinwich.sdk.feature.ana

import com.grinwich.sdk.api.AnalyticsApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.AnaProvisions
import com.grinwich.sdk.contracts.CoreProvisions
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Resolver

class AnaProvider : FeatureProvider<AnaProvisions>(AnaProvisions::class.java) {

    override val services: Map<Class<*>, (AnaProvisions) -> Any> = mapOf(
        AnalyticsApi::class.java to AnaProvisions::analytics,
    )

    override fun build(resolver: Resolver, logger: SdkLogger): AnaProvisions =
        buildAnaProvisions(resolver.provision(CoreProvisions::class.java), logger)
}

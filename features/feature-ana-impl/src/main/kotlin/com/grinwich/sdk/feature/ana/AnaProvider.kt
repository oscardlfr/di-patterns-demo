package com.grinwich.sdk.feature.ana

import com.grinwich.sdk.api.AnalyticsApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver

/** Analytics provider with flavor [Flavor.DAGGER]. Consumed by pattern H. */
class AnaProvider : FeatureProvider() {
    override val flavor = Flavor.DAGGER
    override val services = setOf(AnalyticsApi::class.java)

    override fun build(resolver: Resolver): Map<Class<*>, Any> =
        mapOf(AnalyticsApi::class.java to buildAnalyticsService(resolver.get(SdkLogger::class.java)))
}

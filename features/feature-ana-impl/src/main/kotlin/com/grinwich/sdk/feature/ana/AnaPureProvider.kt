package com.grinwich.sdk.feature.ana

import com.grinwich.sdk.api.AnalyticsApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver

/** Analytics provider with flavor [Flavor.PURE]. Consumed by pattern I. */
class AnaPureProvider : FeatureProvider() {
    override val flavor = Flavor.PURE
    override val services = setOf(AnalyticsApi::class.java)

    override fun build(resolver: Resolver): Map<Class<*>, Any> =
        mapOf(AnalyticsApi::class.java to DefaultAnalyticsService(resolver.get(SdkLogger::class.java)))
}

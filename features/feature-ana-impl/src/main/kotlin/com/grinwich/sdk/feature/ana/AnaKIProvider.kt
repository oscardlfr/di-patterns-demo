package com.grinwich.sdk.feature.ana

import com.grinwich.sdk.api.AnalyticsApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

@Component
internal abstract class KIAnaComponent(
    @get:Provides val logger: SdkLogger,
) {
    abstract val analytics: AnalyticsApi

    @Provides fun analyticsApi(): AnalyticsApi = DefaultAnalyticsService(logger)
}

/** Analytics provider with flavor [Flavor.KI]. Consumed by pattern J. */
class AnaKIProvider : FeatureProvider() {
    override val flavor = Flavor.KI
    override val services = setOf(AnalyticsApi::class.java)

    override fun build(resolver: Resolver): Map<Class<*>, Any> {
        val component = KIAnaComponent::class.create(logger = resolver.get(SdkLogger::class.java))
        return mapOf(AnalyticsApi::class.java to component.analytics)
    }
}

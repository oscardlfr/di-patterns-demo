package com.grinwich.sdk.feature.ana

import com.grinwich.sdk.api.AnalyticsApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.*
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

@Component
abstract class KIAnaComponent(
    @get:Provides val logger: SdkLogger,
) {
    abstract val analytics: AnalyticsApi

    @Provides fun analyticsApi(): AnalyticsApi = DefaultAnalyticsService(logger)
}

class AnaKIProvider : KIFeatureProvider<AnaProvisions>(AnaProvisions::class.java) {
    override val services: Map<Class<*>, (AnaProvisions) -> Any> = mapOf(
        AnalyticsApi::class.java to AnaProvisions::analytics,
    )
    override fun build(resolver: Resolver): AnaProvisions {
        val component = KIAnaComponent::class.create(logger = resolver.logger)
        val analytics = component.analytics
        return object : AnaProvisions {
            override fun analytics() = analytics
        }
    }
}

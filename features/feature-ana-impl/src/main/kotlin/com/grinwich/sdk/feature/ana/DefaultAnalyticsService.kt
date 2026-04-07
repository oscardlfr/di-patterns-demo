package com.grinwich.sdk.feature.ana

import com.grinwich.sdk.api.AnalyticsApi
import com.grinwich.sdk.api.SdkLogger

internal class DefaultAnalyticsService(
    private val logger: SdkLogger,
) : AnalyticsApi {

    private val _events = mutableListOf<String>()

    override fun trackEvent(name: String, properties: Map<String, String>) {
        val entry = if (properties.isEmpty()) name else "$name $properties"
        logger.d("Analytics", "Track: $entry")
        _events.add(entry)
    }

    override fun getTrackedEvents(): List<String> = _events.toList()
}

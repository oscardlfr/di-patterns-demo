package com.grinwich.sdk.api

/**
 * Case 1: ZERO cross-feature deps.
 * Only needs CoreApis (logger, config). Completely standalone.
 */
interface AnalyticsApi {
    fun trackEvent(name: String, properties: Map<String, String> = emptyMap())
    fun getTrackedEvents(): List<String>
}

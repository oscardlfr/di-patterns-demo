package com.grinwich.sdk.api

/**
 * SDK configuration passed at init time.
 */
data class SdkConfig(
    val debug: Boolean = false,
    val apiBaseUrl: String = "https://api.example.com",
)

// SdkLogger → :sdk:observability-api
// CoreApis → :sdk:api (umbrella, depends on both core-api + observability-api)

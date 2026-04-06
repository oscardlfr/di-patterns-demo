package com.grinwich.sdk.api

/**
 * SDK configuration passed at init time.
 */
data class SdkConfig(
    val debug: Boolean = false,
    val apiBaseUrl: String = "https://api.example.com",
)

/**
 * Shared infrastructure -- logger, config.
 * In per-feature approaches (B, C) this is the bridge between isolated Components.
 * In single-graph approaches (A, Koin) this exists only as a convenience.
 */
interface CoreApis {
    val config: SdkConfig
    val logger: SdkLogger
}

interface SdkLogger {
    fun d(tag: String, msg: String)
    fun e(tag: String, msg: String, throwable: Throwable? = null)
}

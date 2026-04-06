package com.grinwich.sdk.api

/**
 * Shared infrastructure -- logger, config.
 * In per-feature approaches (B, C) this is the bridge between isolated Components.
 * In single-graph approaches (A, Koin) this exists only as a convenience.
 */
interface CoreApis {
    val config: SdkConfig
    val logger: SdkLogger
}

// All other types re-exported via api() dependencies in build.gradle.kts:
// - SdkConfig                        → from :sdk:feature-core-api
// - SdkLogger, AndroidSdkLogger      → from :sdk:observability-api
// - EncryptionApi, HashApi    → from :sdk:feature-enc-api
// - AuthApi, AuthToken            → from :sdk:feature-auth-api
// - StorageApi              → from :sdk:feature-stor-api
// - AnalyticsApi                  → from :sdk:feature-ana-api
// - SyncApi, SyncResult           → from :sdk:feature-syn-api

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
// - EncryptionService, HashService    → from :sdk:feature-enc-api
// - AuthService, AuthToken            → from :sdk:feature-auth-api
// - SecureStorageService              → from :sdk:feature-stor-api
// - AnalyticsService                  → from :sdk:feature-ana-api
// - SyncService, SyncResult           → from :sdk:feature-syn-api

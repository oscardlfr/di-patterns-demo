package com.grinwich.sdk.api

/**
 * Which persistence backend Storage uses.
 *
 * - FAKE: in-memory HashMap, zero I/O. Isolates DI framework cost.
 * - SHARED_PREFS: SharedPreferences, synchronous I/O.
 * - DATA_STORE: Jetpack DataStore, async I/O (coroutines). Production default.
 */
enum class StorageBackend { FAKE, SHARED_PREFS, DATA_STORE }

/**
 * SDK configuration passed at init time.
 */
data class SdkConfig(
    val debug: Boolean = false,
    val apiBaseUrl: String = "https://api.example.com",
    val storageBackend: StorageBackend = StorageBackend.DATA_STORE,
)

// SdkLogger → :sdk:observability-api
// CoreApis → :sdk:api (umbrella, depends on both core-api + observability-api)

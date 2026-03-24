package com.grinwich.sdk.api

/**
 * Encrypts and decrypts strings. SDK consumers depend on this interface only.
 */
interface EncryptionService {
    fun encrypt(plaintext: String): String
    fun decrypt(encrypted: String): String
}

/**
 * Hashes byte arrays. Used by Storage for integrity checks.
 */
interface HashService {
    fun sha256(input: ByteArray): ByteArray
    fun sha256Hex(input: String): String
}

/**
 * Authenticates users and provides auth tokens.
 */
interface AuthService {
    fun login(username: String, password: String): AuthToken
    fun refreshToken(token: AuthToken): AuthToken
    fun isAuthenticated(): Boolean
}

data class AuthToken(val accessToken: String, val expiresInSeconds: Long)

/**
 * Stores data securely. Depends on EncryptionService internally.
 */
interface SecureStorageService {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun remove(key: String)
    fun clear()
}

/**
 * SDK configuration passed at init time.
 */
data class SdkConfig(
    val debug: Boolean = false,
    val apiBaseUrl: String = "https://api.example.com",
)

/**
 * Shared infrastructure — logger, config.
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

// ============================================================
// LAZY INIT TEST FEATURES
// ============================================================

/**
 * Case 1: ZERO cross-feature deps.
 * Only needs CoreApis (logger, config). Completely standalone.
 */
interface AnalyticsService {
    fun trackEvent(name: String, properties: Map<String, String> = emptyMap())
    fun getTrackedEvents(): List<String>
}

/**
 * Case 2: HEAVY cross-feature deps.
 * Needs Auth (for user identity), Storage (for offline queue),
 * and Encryption (for payload signing).
 */
interface SyncService {
    fun sync(): SyncResult
    fun pendingCount(): Int
}

data class SyncResult(
    val uploaded: Int,
    val downloaded: Int,
    val userId: String,
    val encryptedPayload: String,
)

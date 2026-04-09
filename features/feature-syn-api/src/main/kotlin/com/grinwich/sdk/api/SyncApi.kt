package com.grinwich.sdk.api

/**
 * Case 2: HEAVY cross-feature deps.
 * Needs Auth (for user identity), Storage (for offline queue),
 * and Encryption (for payload signing).
 */
interface SyncApi {
    suspend fun sync(): SyncResult
    suspend fun pendingCount(): Int
}

data class SyncResult(
    val uploaded: Int,
    val downloaded: Int,
    val userId: String,
    val encryptedPayload: String,
)

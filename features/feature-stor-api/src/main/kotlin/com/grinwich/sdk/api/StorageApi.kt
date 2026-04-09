package com.grinwich.sdk.api

/**
 * Stores data securely. Depends on EncryptionApi internally.
 */
interface StorageApi {
    suspend fun put(key: String, value: String)
    suspend fun get(key: String): String?
    suspend fun remove(key: String)
    suspend fun clear()
}

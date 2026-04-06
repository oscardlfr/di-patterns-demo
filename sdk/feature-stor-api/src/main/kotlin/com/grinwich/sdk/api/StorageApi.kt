package com.grinwich.sdk.api

/**
 * Stores data securely. Depends on EncryptionService internally.
 */
interface SecureStorageService {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun remove(key: String)
    fun clear()
}

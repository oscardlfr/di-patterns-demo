package com.grinwich.sdk.feature.stor

import com.grinwich.sdk.api.EncryptionService
import com.grinwich.sdk.api.HashService
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.api.SecureStorageService

internal class DefaultSecureStorageService(
    private val encryption: EncryptionService,
    private val hash: HashService,
    private val logger: SdkLogger,
) : SecureStorageService {

    private val store = mutableMapOf<String, String>()

    override fun put(key: String, value: String) {
        val hashedKey = hash.sha256Hex(key)
        val encryptedValue = encryption.encrypt(value)
        logger.d("Storage", "PUT $hashedKey → ${encryptedValue.take(15)}...")
        store[hashedKey] = encryptedValue
    }

    override fun get(key: String): String? {
        val hashedKey = hash.sha256Hex(key)
        val encrypted = store[hashedKey] ?: return null
        return encryption.decrypt(encrypted)
    }

    override fun remove(key: String) {
        store.remove(hash.sha256Hex(key))
    }

    override fun clear() {
        store.clear()
    }
}

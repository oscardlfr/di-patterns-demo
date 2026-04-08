package com.grinwich.sdk.feature.stor

import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.api.StorageApi
import javax.inject.Inject

internal class DefaultSecureStorageService @Inject constructor(
    private val encryption: EncryptionApi,
    private val hash: HashApi,
    private val logger: SdkLogger,
) : StorageApi {

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

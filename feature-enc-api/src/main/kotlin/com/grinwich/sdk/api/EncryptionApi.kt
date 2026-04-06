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

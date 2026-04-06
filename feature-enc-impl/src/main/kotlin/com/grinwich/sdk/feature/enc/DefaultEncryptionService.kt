package com.grinwich.sdk.feature.enc

import com.grinwich.sdk.api.EncryptionService
import com.grinwich.sdk.api.HashService
import com.grinwich.sdk.api.SdkLogger

internal class DefaultEncryptionService(private val logger: SdkLogger) : EncryptionService {

    override fun encrypt(plaintext: String): String {
        logger.d("Encryption", "Encrypting ${plaintext.length} chars")
        return "ENC[${plaintext.reversed()}]"
    }

    override fun decrypt(encrypted: String): String {
        logger.d("Encryption", "Decrypting")
        require(encrypted.startsWith("ENC[") && encrypted.endsWith("]")) {
            "Invalid encrypted format"
        }
        return encrypted.removePrefix("ENC[").removeSuffix("]").reversed()
    }
}

internal class DefaultHashService : HashService {

    override fun sha256(input: ByteArray): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(input)
    }

    override fun sha256Hex(input: String): String =
        sha256(input.toByteArray()).joinToString("") { "%02x".format(it) }
}

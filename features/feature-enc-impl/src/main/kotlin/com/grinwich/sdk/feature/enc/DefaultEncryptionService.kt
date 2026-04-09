package com.grinwich.sdk.feature.enc

import android.util.Base64
import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi
import com.grinwich.sdk.api.SdkLogger
import javax.inject.Inject

internal class DefaultEncryptionService @Inject constructor(
    private val logger: SdkLogger,
) : EncryptionApi {

    override fun encrypt(plaintext: String): String {
        logger.d("Encryption", "Encrypting ${plaintext.length} chars")
        return Base64.encodeToString(plaintext.toByteArray(), Base64.NO_WRAP)
    }

    override fun decrypt(encrypted: String): String {
        logger.d("Encryption", "Decrypting")
        return String(Base64.decode(encrypted, Base64.NO_WRAP))
    }
}

internal class DefaultHashService @Inject constructor() : HashApi {

    override fun sha256(input: ByteArray): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(input)
    }

    override fun sha256Hex(input: String): String =
        sha256(input.toByteArray()).joinToString("") { "%02x".format(it) }
}

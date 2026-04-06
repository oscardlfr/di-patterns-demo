package com.grinwich.sdk.feature.auth

import com.grinwich.sdk.api.AuthService
import com.grinwich.sdk.api.AuthToken
import com.grinwich.sdk.api.EncryptionService
import com.grinwich.sdk.api.SdkLogger

internal class DefaultAuthService(
    private val encryption: EncryptionService,
    private val logger: SdkLogger,
) : AuthService {

    private var currentToken: AuthToken? = null

    override fun login(username: String, password: String): AuthToken {
        logger.d("Auth", "Login: $username")
        val encryptedPass = encryption.encrypt(password)
        logger.d("Auth", "Password encrypted: ${encryptedPass.take(10)}...")
        val token = AuthToken(
            accessToken = "tok_${username}_${System.currentTimeMillis()}",
            expiresInSeconds = 3600,
        )
        currentToken = token
        return token
    }

    override fun refreshToken(token: AuthToken): AuthToken {
        logger.d("Auth", "Refreshing token")
        return token.copy(
            accessToken = "tok_refreshed_${System.currentTimeMillis()}",
            expiresInSeconds = 3600,
        )
    }

    override fun isAuthenticated(): Boolean = currentToken != null
}

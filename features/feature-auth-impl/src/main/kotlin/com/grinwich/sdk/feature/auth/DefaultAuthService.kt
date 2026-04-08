package com.grinwich.sdk.feature.auth

import com.grinwich.sdk.api.AuthApi
import com.grinwich.sdk.api.AuthToken
import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.SdkLogger
import javax.inject.Inject

internal class DefaultAuthService @Inject constructor(
    private val encryption: EncryptionApi,
    private val logger: SdkLogger,
) : AuthApi {

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

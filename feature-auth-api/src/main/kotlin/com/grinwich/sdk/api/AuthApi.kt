package com.grinwich.sdk.api

/**
 * Authenticates users and provides auth tokens.
 */
interface AuthService {
    fun login(username: String, password: String): AuthToken
    fun refreshToken(token: AuthToken): AuthToken
    fun isAuthenticated(): Boolean
}

data class AuthToken(val accessToken: String, val expiresInSeconds: Long)

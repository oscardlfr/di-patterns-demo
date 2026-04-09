package com.grinwich.sample.multimodule.data

import com.grinwich.sdk.api.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Example repository that uses SDK services via Dagger constructor injection.
 *
 * This class has ZERO knowledge of MultiModuleSdkH, ServiceLoader, FeatureProviders,
 * or any SDK internal. It only sees API interfaces (AuthApi, StorageApi, AnalyticsApi).
 *
 * Dagger provides the implementations via [SdkBridgeModule].
 */
@Singleton
class UserRepository @Inject constructor(
    private val auth: AuthApi,
    private val storage: StorageApi,
    private val analytics: AnalyticsApi,
) {

    suspend fun login(username: String, password: String): AuthToken {
        val token = auth.login(username, password)
        storage.put("last_user", username)
        analytics.trackEvent("login", mapOf("user" to username))
        return token
    }

    fun isLoggedIn(): Boolean = auth.isAuthenticated()

    suspend fun lastUser(): String? = storage.get("last_user")

    fun trackScreen(screenName: String) {
        analytics.trackEvent("screen_view", mapOf("screen" to screenName))
    }
}

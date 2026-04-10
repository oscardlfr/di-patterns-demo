package com.grinwich.sdk.common

import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.grinwich.sdk.api.*
import com.grinwich.sdk.feature.observability.AndroidSdkLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// ============================================================
// CoreApis implementation
// ============================================================

class CoreApisImpl(
    override val config: SdkConfig,
    override val logger: SdkLogger = AndroidSdkLogger(),
) : CoreApis

// ============================================================
// Encryption — no cross-feature deps
// ============================================================

class DefaultEncryptionService(private val logger: SdkLogger) : EncryptionApi {

    override fun encrypt(plaintext: String): String {
        logger.d("Encryption", "Encrypting ${plaintext.length} chars")
        return Base64.encodeToString(plaintext.toByteArray(), Base64.NO_WRAP)
    }

    override fun decrypt(encrypted: String): String {
        logger.d("Encryption", "Decrypting")
        return String(Base64.decode(encrypted, Base64.NO_WRAP))
    }
}

class DefaultHashService : HashApi {

    override fun sha256(input: ByteArray): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(input)
    }

    override fun sha256Hex(input: String): String =
        sha256(input.toByteArray()).joinToString("") { "%02x".format(it) }
}

// ============================================================
// Auth — depends on EncryptionApi (cross-feature!)
// ============================================================

class DefaultAuthService(
    private val encryption: EncryptionApi,
    private val logger: SdkLogger,
) : AuthApi {

    private var currentToken: AuthToken? = null

    override fun login(username: String, password: String): AuthToken {
        logger.d("Auth", "Login: $username")
        // Encrypt the password before "sending" — demonstrates cross-feature dep
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

// ============================================================
// Storage — THREE backends for benchmark comparison
// ============================================================
// StorageBackend enum is now in :features:feature-core-api (com.grinwich.sdk.api.StorageBackend)
// so both monolithic and multi-module patterns share the same type.

/**
 * Fake backend — in-memory HashMap, zero I/O, zero Context.
 *
 * No necesita Context ni disco. Mide SOLO el coste del DI framework:
 * cuanto tarda el SDK en resolver servicios, construir provisions, y ejecutar
 * logica de negocio (encrypt, hash, auth) SIN el overhead de persistencia.
 *
 * Ideal para comparar patrones DI entre si (D vs H vs Koin) sin que
 * el ruido de I/O enmascare las diferencias.
 */
class FakeStorageService(
    private val encryption: EncryptionApi,
    private val hash: HashApi,
    private val logger: SdkLogger,
) : StorageApi {

    private val store = mutableMapOf<String, String>()

    override suspend fun put(key: String, value: String) {
        val hashedKey = hash.sha256Hex(key)
        val encryptedValue = encryption.encrypt(value)
        logger.d("Storage", "PUT $hashedKey")
        store[hashedKey] = encryptedValue
    }

    override suspend fun get(key: String): String? {
        val hashedKey = hash.sha256Hex(key)
        val encrypted = store[hashedKey] ?: return null
        return encryption.decrypt(encrypted)
    }

    override suspend fun remove(key: String) {
        store.remove(hash.sha256Hex(key))
    }

    override suspend fun clear() {
        store.clear()
    }
}

/** SharedPreferences backend — synchronous, fast, legacy. */
class SharedPrefsStorageService(
    context: android.content.Context,
    private val encryption: EncryptionApi,
    private val hash: HashApi,
    private val logger: SdkLogger,
) : StorageApi {

    private val prefs = context.getSharedPreferences("sdk_mono_storage", android.content.Context.MODE_PRIVATE)

    override suspend fun put(key: String, value: String) {
        val hashedKey = hash.sha256Hex(key)
        val encryptedValue = encryption.encrypt(value)
        logger.d("Storage", "PUT $hashedKey → ${encryptedValue.take(15)}...")
        prefs.edit().putString(hashedKey, encryptedValue).apply()
    }

    override suspend fun get(key: String): String? {
        val hashedKey = hash.sha256Hex(key)
        val encrypted = prefs.getString(hashedKey, null) ?: return null
        return encryption.decrypt(encrypted)
    }

    override suspend fun remove(key: String) {
        prefs.edit().remove(hash.sha256Hex(key)).apply()
    }

    override suspend fun clear() {
        prefs.edit().clear().apply()
    }
}

/** DataStore backend — async, modern, same as multi-module feature-stor-impl. */
private val android.content.Context.monoDataStore by preferencesDataStore(name = "sdk_mono_datastore")

class DataStoreStorageService(
    context: android.content.Context,
    private val encryption: EncryptionApi,
    private val hash: HashApi,
    private val logger: SdkLogger,
) : StorageApi {

    private val dataStore = context.monoDataStore

    override suspend fun put(key: String, value: String) {
        val hashedKey = hash.sha256Hex(key)
        val encryptedValue = encryption.encrypt(value)
        logger.d("Storage", "PUT $hashedKey")
        dataStore.edit { prefs -> prefs[stringPreferencesKey(hashedKey)] = encryptedValue }
    }

    override suspend fun get(key: String): String? {
        val hashedKey = hash.sha256Hex(key)
        return dataStore.data.map { prefs -> prefs[stringPreferencesKey(hashedKey)] }.first()
            ?.let { encryption.decrypt(it) }
    }

    override suspend fun remove(key: String) {
        val hashedKey = hash.sha256Hex(key)
        dataStore.edit { prefs -> prefs.remove(stringPreferencesKey(hashedKey)) }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}

/** Backward-compat alias — resolves to DataStore (parity with multi-module). */
@Suppress("unused")
@Deprecated("Use DataStoreStorageService or SharedPrefsStorageService explicitly", ReplaceWith("DataStoreStorageService"))
typealias DefaultSecureStorageService = DataStoreStorageService

// ============================================================
// Analytics — ZERO cross-feature deps (Case 1: standalone lazy init)
// ============================================================

class DefaultAnalyticsService(
    private val logger: SdkLogger,
) : AnalyticsApi {

    private val _events = mutableListOf<String>()

    override fun trackEvent(name: String, properties: Map<String, String>) {
        val entry = if (properties.isEmpty()) name else "$name $properties"
        logger.d("Analytics", "Track: $entry")
        _events.add(entry)
    }

    override fun getTrackedEvents(): List<String> = _events.toList()
}

// ============================================================
// Sync — HEAVY cross-feature deps (Case 2: Auth + Storage + Encryption)
// ============================================================

class DefaultSyncService(
    private val auth: AuthApi,
    private val storage: StorageApi,
    private val encryption: EncryptionApi,
    private val logger: SdkLogger,
) : SyncApi {

    override suspend fun sync(): SyncResult {
        // 1. Requires authenticated user
        check(auth.isAuthenticated()) { "Must be logged in to sync. Call auth.login() first." }

        // 2. Read pending data from secure storage
        val pending = storage.get("sync-queue") ?: "no-pending-data"
        logger.d("Sync", "Syncing: $pending")

        // 3. Encrypt the payload before "sending"
        val encrypted = encryption.encrypt(pending)

        // 4. Simulate upload/download
        storage.put("last-sync", System.currentTimeMillis().toString())

        return SyncResult(
            uploaded = 3,
            downloaded = 5,
            userId = "current-user",
            encryptedPayload = encrypted,
        )
    }

    override suspend fun pendingCount(): Int {
        return if (storage.get("sync-queue") != null) 1 else 0
    }
}

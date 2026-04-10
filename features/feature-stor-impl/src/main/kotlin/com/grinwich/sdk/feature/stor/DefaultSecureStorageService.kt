package com.grinwich.sdk.feature.stor

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.api.StorageApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// ============================================================
// Fake — in-memory HashMap, zero I/O
// ============================================================

/** Fake: in-memory HashMap, zero I/O. Isolates DI framework cost. */
internal class FakeStorageService(
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

// ============================================================
// SharedPreferences — synchronous I/O
// ============================================================

/** SharedPreferences: synchronous I/O. */
internal class SharedPrefsStorageService(
    context: Context,
    private val encryption: EncryptionApi,
    private val hash: HashApi,
    private val logger: SdkLogger,
) : StorageApi {

    private val prefs = context.getSharedPreferences("sdk_mm_storage", Context.MODE_PRIVATE)

    override suspend fun put(key: String, value: String) {
        val hashedKey = hash.sha256Hex(key)
        val encryptedValue = encryption.encrypt(value)
        logger.d("Storage", "PUT $hashedKey")
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

// ============================================================
// DataStore — async I/O with coroutines (production backend)
// ============================================================

private val Context.sdkDataStore: DataStore<Preferences> by preferencesDataStore(name = "sdk_secure_storage")

/** DataStore: async I/O with coroutines. Production backend. */
internal class DataStoreStorageService(
    context: Context,
    private val encryption: EncryptionApi,
    private val hash: HashApi,
    private val logger: SdkLogger,
) : StorageApi {

    private val dataStore = context.sdkDataStore

    override suspend fun put(key: String, value: String) {
        val hashedKey = hash.sha256Hex(key)
        val encryptedValue = encryption.encrypt(value)
        logger.d("Storage", "PUT $hashedKey")
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(hashedKey)] = encryptedValue
        }
    }

    override suspend fun get(key: String): String? {
        val hashedKey = hash.sha256Hex(key)
        val encrypted = dataStore.data.map { prefs ->
            prefs[stringPreferencesKey(hashedKey)]
        }.first()
        return encrypted?.let { encryption.decrypt(it) }
    }

    override suspend fun remove(key: String) {
        val hashedKey = hash.sha256Hex(key)
        dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(hashedKey))
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}

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

private val Context.sdkDataStore: DataStore<Preferences> by preferencesDataStore(name = "sdk_secure_storage")

internal class DefaultSecureStorageService(
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

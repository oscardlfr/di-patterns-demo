package com.grinwich.sdk.api

/**
 * Qué backend de persistencia usa Storage.
 *
 * - FAKE: HashMap in-memory, cero I/O. Aísla el coste del framework DI.
 * - SHARED_PREFS: SharedPreferences, I/O síncrono.
 * - DATA_STORE: Jetpack DataStore, I/O async (coroutines). Default producción.
 */
enum class StorageBackend { FAKE, SHARED_PREFS, DATA_STORE }

/**
 * Configuración del SDK pasada en init.
 *
 * Vive en `:sdk:api` (superficie consumidor) para que `di-contracts` no
 * necesite importarla — el Resolver trata SdkConfig como un servicio más
 * vía `get(SdkConfig::class.java)` sin conocer el tipo concreto.
 */
data class SdkConfig(
    val debug: Boolean = false,
    val apiBaseUrl: String = "https://api.example.com",
    val storageBackend: StorageBackend = StorageBackend.DATA_STORE,
)

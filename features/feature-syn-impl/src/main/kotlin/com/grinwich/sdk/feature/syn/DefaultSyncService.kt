package com.grinwich.sdk.feature.syn

import com.grinwich.sdk.api.*
import javax.inject.Inject

internal class DefaultSyncService @Inject constructor(
    private val auth: AuthApi,
    private val storage: StorageApi,
    private val encryption: EncryptionApi,
    private val logger: SdkLogger,
) : SyncApi {

    override fun sync(): SyncResult {
        check(auth.isAuthenticated()) { "Must be logged in to sync. Call auth.login() first." }

        val pending = storage.get("sync-queue") ?: "no-pending-data"
        logger.d("Sync", "Syncing: $pending")

        val encrypted = encryption.encrypt(pending)
        storage.put("last-sync", System.currentTimeMillis().toString())

        return SyncResult(
            uploaded = 3,
            downloaded = 5,
            userId = "current-user",
            encryptedPayload = encrypted,
        )
    }

    override fun pendingCount(): Int {
        return if (storage.get("sync-queue") != null) 1 else 0
    }
}

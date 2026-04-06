package com.grinwich.sdk.feature.syn

import com.grinwich.sdk.api.*

internal class DefaultSyncService(
    private val auth: AuthService,
    private val storage: SecureStorageService,
    private val encryption: EncryptionService,
    private val logger: SdkLogger,
) : SyncService {

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

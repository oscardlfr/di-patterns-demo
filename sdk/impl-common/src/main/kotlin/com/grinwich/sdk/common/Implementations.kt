package com.grinwich.sdk.common

import android.util.Log
import com.grinwich.sdk.api.*

// ============================================================
// Logger
// ============================================================

class AndroidSdkLogger : SdkLogger {
    override fun d(tag: String, msg: String) = Log.d("SDK-$tag", msg).let { }
    override fun e(tag: String, msg: String, throwable: Throwable?) {
        Log.e("SDK-$tag", msg, throwable)
    }
}

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

class DefaultEncryptionService(private val logger: SdkLogger) : EncryptionService {

    override fun encrypt(plaintext: String): String {
        logger.d("Encryption", "Encrypting ${plaintext.length} chars")
        // Fake AES — real impl would use javax.crypto
        return "ENC[${plaintext.reversed()}]"
    }

    override fun decrypt(encrypted: String): String {
        logger.d("Encryption", "Decrypting")
        require(encrypted.startsWith("ENC[") && encrypted.endsWith("]")) {
            "Invalid encrypted format"
        }
        return encrypted.removePrefix("ENC[").removeSuffix("]").reversed()
    }
}

class DefaultHashService : HashService {

    override fun sha256(input: ByteArray): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(input)
    }

    override fun sha256Hex(input: String): String =
        sha256(input.toByteArray()).joinToString("") { "%02x".format(it) }
}

// ============================================================
// Auth — depends on EncryptionService (cross-feature!)
// ============================================================

class DefaultAuthService(
    private val encryption: EncryptionService,
    private val logger: SdkLogger,
) : AuthService {

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
// Storage — depends on EncryptionService AND HashService (cross-feature!)
// ============================================================

class DefaultSecureStorageService(
    private val encryption: EncryptionService,
    private val hash: HashService,
    private val logger: SdkLogger,
) : SecureStorageService {

    private val store = mutableMapOf<String, String>()

    override fun put(key: String, value: String) {
        val hashedKey = hash.sha256Hex(key)
        val encryptedValue = encryption.encrypt(value)
        logger.d("Storage", "PUT $hashedKey → ${encryptedValue.take(15)}...")
        store[hashedKey] = encryptedValue
    }

    override fun get(key: String): String? {
        val hashedKey = hash.sha256Hex(key)
        val encrypted = store[hashedKey] ?: return null
        return encryption.decrypt(encrypted)
    }

    override fun remove(key: String) {
        store.remove(hash.sha256Hex(key))
    }

    override fun clear() {
        store.clear()
    }
}

// ============================================================
// Analytics — ZERO cross-feature deps (Case 1: standalone lazy init)
// ============================================================

class DefaultAnalyticsService(
    private val logger: SdkLogger,
) : AnalyticsService {

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
    private val auth: AuthService,
    private val storage: SecureStorageService,
    private val encryption: EncryptionService,
    private val logger: SdkLogger,
) : SyncService {

    override fun sync(): SyncResult {
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

    override fun pendingCount(): Int {
        return if (storage.get("sync-queue") != null) 1 else 0
    }
}

package com.grinwich.sdk.contracts

import com.grinwich.sdk.api.*

// ============================================================
// Provision interfaces — plain Kotlin interfaces, NO @Component.
//
// Dagger's `dependencies=[...]` accepts ANY interface with
// provision methods. It does NOT require @Component.
//
// Each feature's @Component implements the relevant provision
// interface. Features depend on provision interfaces (contracts),
// never on other features' @Component classes (implementations).
//
// This module has ZERO Dagger dependency — it's pure Kotlin.
// ============================================================

/** Core services available to all features. */
interface CoreProvisions {
    fun config(): SdkConfig
    fun logger(): SdkLogger
}

/** Encryption services — consumed by Auth, Storage, Sync. */
interface EncProvisions {
    fun encryption(): EncryptionService
    fun hash(): HashService
}

/** Auth services — consumed by Sync. */
interface AuthProvisions {
    fun auth(): AuthService
}

/** Storage services — consumed by Sync. */
interface StorProvisions {
    fun storage(): SecureStorageService
}

/** Analytics services — standalone, no consumers. */
interface AnaProvisions {
    fun analytics(): AnalyticsService
}

/** Sync services — leaf node, depends on Auth + Storage + Encryption. */
interface SynProvisions {
    fun sync(): SyncService
}

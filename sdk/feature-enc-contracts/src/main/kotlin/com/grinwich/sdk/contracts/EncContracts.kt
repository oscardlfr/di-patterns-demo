package com.grinwich.sdk.contracts

import com.grinwich.sdk.api.EncryptionService
import com.grinwich.sdk.api.HashService
import javax.inject.Scope

/** Encryption services — consumed by Auth, Storage, Sync. */
interface EncProvisions {
    fun encryption(): EncryptionService
    fun hash(): HashService
}

@Scope @Retention(AnnotationRetention.RUNTIME) annotation class EncScope

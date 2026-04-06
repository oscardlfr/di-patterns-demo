package com.grinwich.sdk.contracts

import com.grinwich.sdk.api.SecureStorageService
import javax.inject.Scope

/** Storage services — consumed by Sync. */
interface StorProvisions {
    fun storage(): SecureStorageService
}

@Scope @Retention(AnnotationRetention.RUNTIME) annotation class StorScope

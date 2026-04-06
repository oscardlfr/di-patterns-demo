package com.grinwich.sdk.contracts

import com.grinwich.sdk.api.SyncService
import javax.inject.Scope

/** Sync services — leaf node, depends on Auth + Storage + Encryption. */
interface SynProvisions {
    fun sync(): SyncService
}

@Scope @Retention(AnnotationRetention.RUNTIME) annotation class SynScope

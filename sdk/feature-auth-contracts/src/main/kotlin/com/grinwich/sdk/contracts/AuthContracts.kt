package com.grinwich.sdk.contracts

import com.grinwich.sdk.api.AuthService
import javax.inject.Scope

/** Auth services — consumed by Sync. */
interface AuthProvisions {
    fun auth(): AuthService
}

@Scope @Retention(AnnotationRetention.RUNTIME) annotation class AuthScope

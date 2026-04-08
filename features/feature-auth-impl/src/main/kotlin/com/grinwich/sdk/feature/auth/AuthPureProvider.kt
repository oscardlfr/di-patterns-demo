package com.grinwich.sdk.feature.auth

import com.grinwich.sdk.api.AuthApi
import com.grinwich.sdk.contracts.*

class AuthPureProvider : PureFeatureProvider<AuthProvisions>(AuthProvisions::class.java) {
    override val services: Map<Class<*>, (AuthProvisions) -> Any> = mapOf(
        AuthApi::class.java to AuthProvisions::auth,
    )
    override fun build(resolver: Resolver): AuthProvisions {
        val enc = resolver.provision(EncProvisions::class.java)
        val logger = resolver.logger
        val auth = DefaultAuthService(enc.encryption(), logger)
        return object : AuthProvisions {
            override fun auth() = auth
        }
    }
}

package com.grinwich.sdk.feature.auth

import com.grinwich.sdk.api.AuthApi
import com.grinwich.sdk.contracts.*

class AuthProvider : FeatureProvider<AuthProvisions>(AuthProvisions::class.java) {

    override val services: Map<Class<*>, (AuthProvisions) -> Any> = mapOf(
        AuthApi::class.java to AuthProvisions::auth,
    )

    override fun build(resolver: Resolver): AuthProvisions =
        buildAuthProvisions(resolver.provision(CoreProvisions::class.java), resolver.logger, resolver.provision(EncProvisions::class.java))
}

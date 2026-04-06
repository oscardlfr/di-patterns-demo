package com.grinwich.sdk.feature.auth

import com.grinwich.sdk.api.AuthApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.AuthProvisions
import com.grinwich.sdk.contracts.CoreProvisions
import com.grinwich.sdk.contracts.EncProvisions
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Resolver

class AuthProvider : FeatureProvider<AuthProvisions>(AuthProvisions::class.java) {

    override val services: Map<Class<*>, (AuthProvisions) -> Any> = mapOf(
        AuthApi::class.java to AuthProvisions::auth,
    )

    override fun build(resolver: Resolver, logger: SdkLogger): AuthProvisions =
        buildAuthProvisions(resolver.provision(CoreProvisions::class.java), logger, resolver.provision(EncProvisions::class.java))
}

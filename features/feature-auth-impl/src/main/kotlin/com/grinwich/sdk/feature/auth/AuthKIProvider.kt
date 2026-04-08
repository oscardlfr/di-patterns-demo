package com.grinwich.sdk.feature.auth

import com.grinwich.sdk.api.AuthApi
import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.*
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

@Component
abstract class KIAuthComponent(
    @get:Provides val encryption: EncryptionApi,
    @get:Provides val logger: SdkLogger,
) {
    abstract val auth: AuthApi

    @Provides fun authApi(): AuthApi = DefaultAuthService(encryption, logger)
}

class AuthKIProvider : KIFeatureProvider<AuthProvisions>(AuthProvisions::class.java) {
    override val services: Map<Class<*>, (AuthProvisions) -> Any> = mapOf(
        AuthApi::class.java to AuthProvisions::auth,
    )
    override fun build(resolver: Resolver): AuthProvisions {
        val enc = resolver.provision(EncProvisions::class.java)
        val component = KIAuthComponent::class.create(
            encryption = enc.encryption(),
            logger = resolver.logger,
        )
        val auth = component.auth  // capture once — singleton within this provision
        return object : AuthProvisions {
            override fun auth() = auth
        }
    }
}

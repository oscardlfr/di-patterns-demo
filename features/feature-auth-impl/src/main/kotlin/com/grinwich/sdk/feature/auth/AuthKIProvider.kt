package com.grinwich.sdk.feature.auth

import com.grinwich.sdk.api.AuthApi
import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

@Component
internal abstract class KIAuthComponent(
    @get:Provides val encryption: EncryptionApi,
    @get:Provides val logger: SdkLogger,
) {
    abstract val auth: AuthApi

    @Provides fun authApi(): AuthApi = DefaultAuthService(encryption, logger)
}

/** Auth provider with flavor [Flavor.KI]. Consumed by pattern J. */
class AuthKIProvider : FeatureProvider() {
    override val flavor = Flavor.KI
    override val services = setOf(AuthApi::class.java)

    override fun build(resolver: Resolver): Map<Class<*>, Any> {
        val component = KIAuthComponent::class.create(
            encryption = resolver.get(EncryptionApi::class.java),
            logger = resolver.get(SdkLogger::class.java),
        )
        return mapOf(AuthApi::class.java to component.auth)
    }
}

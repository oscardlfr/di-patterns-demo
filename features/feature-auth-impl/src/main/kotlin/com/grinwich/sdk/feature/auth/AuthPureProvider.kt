package com.grinwich.sdk.feature.auth

import com.grinwich.sdk.api.AuthApi
import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver

/** Auth provider with flavor [Flavor.PURE]. Consumed by pattern I. */
class AuthPureProvider : FeatureProvider() {
    override val flavor = Flavor.PURE
    override val services = setOf(AuthApi::class.java)

    override fun build(resolver: Resolver): Map<Class<*>, Any> {
        val auth = DefaultAuthService(
            encryption = resolver.get(EncryptionApi::class.java),
            logger = resolver.get(SdkLogger::class.java),
        )
        return mapOf(AuthApi::class.java to auth)
    }
}

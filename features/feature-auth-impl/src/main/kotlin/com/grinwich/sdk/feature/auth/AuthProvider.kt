package com.grinwich.sdk.feature.auth

import com.grinwich.sdk.api.AuthApi
import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver

/** Auth provider with flavor [Flavor.DAGGER]. Consumed by pattern H. */
class AuthProvider : FeatureProvider() {
    override val flavor = Flavor.DAGGER
    override val services = setOf(AuthApi::class.java)

    override fun build(resolver: Resolver): Map<Class<*>, Any> {
        val auth = buildAuthService(
            encryption = resolver.get(EncryptionApi::class.java),
            logger = resolver.get(SdkLogger::class.java),
        )
        return mapOf(AuthApi::class.java to auth)
    }
}

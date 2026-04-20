package com.grinwich.sdk.feature.enc

import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver

/**
 * Encryption feature provider with flavor [Flavor.PURE].
 * Builds directly with constructors — no DI framework.
 * Consumed by pattern I when filtering by PURE.
 */
class EncPureProvider : FeatureProvider() {
    override val flavor = Flavor.PURE
    override val services = setOf(EncryptionApi::class.java, HashApi::class.java)

    override fun build(resolver: Resolver): Map<Class<*>, Any> {
        val logger = resolver.get(SdkLogger::class.java)
        return mapOf(
            EncryptionApi::class.java to DefaultEncryptionService(logger),
            HashApi::class.java to DefaultHashService(),
        )
    }
}

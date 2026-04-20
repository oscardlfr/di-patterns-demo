package com.grinwich.sdk.feature.enc

import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver

/**
 * Encryption feature provider with flavor [Flavor.DAGGER].
 * Consumed by pattern H when filtering by DAGGER after discovery via ServiceLoader.
 */
class EncProvider : FeatureProvider() {
    override val flavor = Flavor.DAGGER
    override val services = setOf(EncryptionApi::class.java, HashApi::class.java)

    override fun build(resolver: Resolver): Map<Class<*>, Any> {
        val bundle = buildEncBundle(resolver.get(SdkLogger::class.java))
        return mapOf(
            EncryptionApi::class.java to bundle.encryption(),
            HashApi::class.java to bundle.hash(),
        )
    }
}

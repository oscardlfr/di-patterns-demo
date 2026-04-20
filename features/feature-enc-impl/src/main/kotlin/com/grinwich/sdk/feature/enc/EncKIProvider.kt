package com.grinwich.sdk.feature.enc

import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject Component for Enc. Internal — only [EncKIProvider] instantiates it.
 */
@Component
internal abstract class KIEncComponent(
    @get:Provides val logger: SdkLogger,
) {
    abstract val encryption: EncryptionApi
    abstract val hash: HashApi

    @Provides fun encryptionApi(): EncryptionApi = DefaultEncryptionService(logger)
    @Provides fun hashApi(): HashApi = DefaultHashService()
}

/**
 * Encryption feature provider with flavor [Flavor.KI].
 * Builds with kotlin-inject. Consumed by pattern J when filtering by KI.
 */
class EncKIProvider : FeatureProvider() {
    override val flavor = Flavor.KI
    override val services = setOf(EncryptionApi::class.java, HashApi::class.java)

    override fun build(resolver: Resolver): Map<Class<*>, Any> {
        val component = KIEncComponent::class.create(logger = resolver.get(SdkLogger::class.java))
        return mapOf(
            EncryptionApi::class.java to component.encryption,
            HashApi::class.java to component.hash,
        )
    }
}

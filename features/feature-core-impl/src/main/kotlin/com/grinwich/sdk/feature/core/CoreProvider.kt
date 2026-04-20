package com.grinwich.sdk.feature.core

import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver

/**
 * Core provider with flavor [Flavor.DAGGER].
 *
 * Core is single-service (only `SdkConfig`) and trivial: no Dagger Component.
 * The Resolver already auto-registers `SdkConfig`, but publishing here is required
 * for patterns (E/E2) that use `ServiceRegistry` instead of `Resolver`.
 */
class CoreProvider : FeatureProvider() {
    override val flavor = Flavor.DAGGER
    override val services = setOf(SdkConfig::class.java)

    override fun build(resolver: Resolver): Map<Class<*>, Any> =
        mapOf(SdkConfig::class.java to resolver.get(SdkConfig::class.java))
}

package com.grinwich.sdk.feature.core

import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver

/**
 * Core provider with flavor [Flavor.KI]. Consumed by pattern J.
 *
 * No-op publisher: `SdkConfig` is supplied by the wiring's
 * `SyntheticFeatureProvider`, not by this provider. See [CoreProvider]
 * for the rationale.
 */
class CoreKIProvider : FeatureProvider() {
    override val flavor = Flavor.KI
    override val services: Set<Class<*>> = emptySet()

    override fun build(resolver: Resolver): Map<Class<*>, Any> = emptyMap()
}

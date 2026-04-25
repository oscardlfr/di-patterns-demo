package com.grinwich.sdk.feature.core

import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver

/**
 * Core provider with flavor [Flavor.PURE]. Consumed by pattern I.
 *
 * No-op publisher: `SdkConfig` is supplied by the wiring's
 * `SyntheticFeatureProvider`, not by this provider. See [CoreProvider]
 * for the rationale.
 */
class CorePureProvider : FeatureProvider() {
    override val flavor = Flavor.PURE
    override val services: Set<Class<*>> = emptySet()

    override fun build(resolver: Resolver): Map<Class<*>, Any> = emptyMap()
}

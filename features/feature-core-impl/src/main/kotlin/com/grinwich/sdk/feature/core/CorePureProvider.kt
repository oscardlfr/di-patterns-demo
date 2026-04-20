package com.grinwich.sdk.feature.core

import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver

/** Core provider with flavor [Flavor.PURE]. Consumed by pattern I. */
class CorePureProvider : FeatureProvider() {
    override val flavor = Flavor.PURE
    override val services = setOf(SdkConfig::class.java)

    override fun build(resolver: Resolver): Map<Class<*>, Any> =
        mapOf(SdkConfig::class.java to resolver.get(SdkConfig::class.java))
}

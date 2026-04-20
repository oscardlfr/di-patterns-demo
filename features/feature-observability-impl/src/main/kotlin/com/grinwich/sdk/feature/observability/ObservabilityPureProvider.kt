package com.grinwich.sdk.feature.observability

import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver

/** Observability provider with flavor [Flavor.PURE]. */
class ObservabilityPureProvider : FeatureProvider() {
    override val flavor = Flavor.PURE
    override val services = setOf(SdkLogger::class.java)

    /** Logger survives shutdown — tied to the app lifecycle, not the SDK lifecycle. */
    override val persistent = true

    override fun build(resolver: Resolver): Map<Class<*>, Any> =
        mapOf(SdkLogger::class.java to buildLogger())
}

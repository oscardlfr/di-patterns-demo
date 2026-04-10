package com.grinwich.sdk.feature.observability

import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.ObservabilityProvisions
import com.grinwich.sdk.contracts.Resolver

class ObservabilityProvider : FeatureProvider<ObservabilityProvisions>(ObservabilityProvisions::class.java) {

    /** Logger sobrevive shutdown — atado al ciclo de vida de la app, no del SDK. */
    override val persistent = true

    override val services: Map<Class<*>, (ObservabilityProvisions) -> Any> = mapOf(
        SdkLogger::class.java to ObservabilityProvisions::logger,
    )

    override fun build(resolver: Resolver): ObservabilityProvisions =
        DaggerObservabilityComponent.create()
}

package com.grinwich.sdk.feature.observability

import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.ObservabilityProvisions
import dagger.BindsInstance
import dagger.Component
import javax.inject.Singleton

/** Factory: builds ObservabilityProvisions without exposing DaggerObservabilityComponent. */
fun buildObservabilityProvisions(logger: SdkLogger): ObservabilityProvisions =
    DaggerObservabilityComponent.builder().logger(logger).build()

@Singleton
@Component
interface ObservabilityComponent : ObservabilityProvisions {

    override fun logger(): SdkLogger

    @Component.Builder interface Builder {
        @BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): ObservabilityComponent
    }
}

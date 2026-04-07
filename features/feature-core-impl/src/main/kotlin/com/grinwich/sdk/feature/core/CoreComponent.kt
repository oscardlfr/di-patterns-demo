package com.grinwich.sdk.feature.core

import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.contracts.CoreProvisions
import dagger.BindsInstance
import dagger.Component
import javax.inject.Singleton

/** Factory: builds CoreProvisions without exposing DaggerCoreComponent. */
fun buildCoreProvisions(config: SdkConfig): CoreProvisions =
    DaggerCoreComponent.builder().config(config).build()

/**
 * CoreComponent — only config. Logger is in ObservabilityComponent.
 */
@Singleton
@Component
interface CoreComponent : CoreProvisions {

    override fun config(): SdkConfig

    @Component.Builder interface Builder {
        @BindsInstance fun config(config: SdkConfig): Builder
        fun build(): CoreComponent
    }
}

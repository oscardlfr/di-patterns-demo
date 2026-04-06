package com.grinwich.sdk.feature.core

import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.CoreProvisions
import dagger.BindsInstance
import dagger.Component
import javax.inject.Singleton

/**
 * CoreComponent implements CoreProvisions (the contract from di-contracts).
 *
 * Other feature modules depend on CoreProvisions, not on this @Component.
 * Only sdk-wiring imports this to call DaggerCoreComponent.builder().
 */
@Singleton
@Component
interface CoreComponent : CoreProvisions {

    override fun config(): SdkConfig
    override fun logger(): SdkLogger

    @Component.Builder interface Builder {
        @BindsInstance fun config(config: SdkConfig): Builder
        @BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): CoreComponent
    }
}

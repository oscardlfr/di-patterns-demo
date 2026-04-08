package com.grinwich.sdk.feature.observability

import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.ObservabilityProvisions
import dagger.Binds
import dagger.Component
import dagger.Module
import javax.inject.Singleton

@Singleton
@Component(modules = [ObservabilityModule::class])
interface ObservabilityComponent : ObservabilityProvisions {
    override fun logger(): SdkLogger
}

@Module
internal abstract class ObservabilityModule {
    @Binds @Singleton
    abstract fun logger(impl: AndroidSdkLogger): SdkLogger
}

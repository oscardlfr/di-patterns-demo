package com.grinwich.sdk.feature.observability

import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.ObservabilityProvisions
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Singleton
@Component(modules = [ObservabilityModule::class])
interface ObservabilityComponent : ObservabilityProvisions {
    override fun logger(): SdkLogger
}

@Module
internal class ObservabilityModule {
    @Provides @Singleton
    fun logger(): SdkLogger = AndroidSdkLogger()
}

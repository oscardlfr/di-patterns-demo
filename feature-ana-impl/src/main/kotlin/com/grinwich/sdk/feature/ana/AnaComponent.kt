package com.grinwich.sdk.feature.ana

import com.grinwich.sdk.api.AnalyticsApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.AnaProvisions
import com.grinwich.sdk.contracts.AnaScope
import com.grinwich.sdk.contracts.CoreProvisions
import dagger.Component
import dagger.Module
import dagger.Provides

/**
 * AnaComponent — standalone, only depends on CoreProvisions.
 * No cross-feature deps. Good test case for lazy init without cascading.
 */
@AnaScope
@Component(
    dependencies = [CoreProvisions::class],
    modules = [AnaModule::class],
)
interface AnaComponent : AnaProvisions {

    override fun analytics(): AnalyticsApi

    @Component.Builder interface Builder {
        fun core(core: CoreProvisions): Builder
        fun build(): AnaComponent
    }
}

@Module
internal class AnaModule {
    @Provides @AnaScope
    fun analytics(logger: SdkLogger): AnalyticsApi = DefaultAnalyticsService(logger)
}

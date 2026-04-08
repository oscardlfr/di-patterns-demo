package com.grinwich.sdk.feature.ana

import com.grinwich.sdk.api.AnalyticsApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.AnaProvisions
import com.grinwich.sdk.contracts.AnaScope
import com.grinwich.sdk.contracts.CoreProvisions
import dagger.Binds
import dagger.BindsInstance
import dagger.Component
import dagger.Module

/** Factory: builds AnaProvisions without exposing DaggerAnaComponent. */
fun buildAnaProvisions(core: CoreProvisions, logger: SdkLogger): AnaProvisions =
    DaggerAnaComponent.builder().core(core).logger(logger).build()

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
        @BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): AnaComponent
    }
}

@Module
internal abstract class AnaModule {
    @Binds @AnaScope
    abstract fun analytics(impl: DefaultAnalyticsService): AnalyticsApi
}

package com.grinwich.sdk.feature.ana

import com.grinwich.sdk.api.AnalyticsApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.AnaScope
import dagger.Binds
import dagger.BindsInstance
import dagger.Component
import dagger.Module

/** Factory: builds [AnalyticsApi] directly — single-service feature. */
fun buildAnalyticsService(logger: SdkLogger): AnalyticsApi =
    DaggerAnaComponent.builder()
        .logger(logger)
        .build()
        .analytics()

/**
 * AnaComponent — single-service Dagger component.
 *
 * Does not declare `dependencies = [...]`. `SdkLogger` enters via `@BindsInstance`.
 */
@AnaScope
@Component(modules = [AnaModule::class])
internal interface AnaComponent {

    fun analytics(): AnalyticsApi

    @Component.Builder interface Builder {
        @BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): AnaComponent
    }
}

@Module
internal abstract class AnaModule {
    @Binds @AnaScope
    abstract fun analytics(impl: DefaultAnalyticsService): AnalyticsApi
}

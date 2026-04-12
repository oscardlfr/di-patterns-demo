package com.grinwich.sdk.feature.ana

import com.grinwich.sdk.api.AnalyticsApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.LazyCreationTracker
import com.grinwich.sdk.contracts.SdkScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Pattern P: kotlin-inject-anvil bindings for Analytics feature. */
@ContributesTo(SdkScope::class)
interface AnvilAnaBindings {
    @SingleIn(SdkScope::class) @Provides fun provideAnalytics(logger: SdkLogger): AnalyticsApi {
        LazyCreationTracker.mark("analytics")
        return DefaultAnalyticsService(logger)
    }
}

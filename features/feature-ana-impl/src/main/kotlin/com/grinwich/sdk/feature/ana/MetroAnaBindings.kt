package com.grinwich.sdk.feature.ana

import com.grinwich.sdk.api.AnalyticsApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.LazyCreationTracker
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** Pattern O: Metro bindings for Analytics feature. */
@ContributesTo(AppScope::class)
interface MetroAnaBindings {
    @SingleIn(AppScope::class) @Provides fun provideAnalytics(logger: SdkLogger): AnalyticsApi {
        LazyCreationTracker.mark("analytics")
        return DefaultAnalyticsService(logger)
    }
}

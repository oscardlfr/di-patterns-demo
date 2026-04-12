package com.grinwich.sdk.feature.ana

import com.grinwich.sdk.api.AnalyticsApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.LazyCreationTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Pattern Q: Hilt-style Dagger module for Analytics feature. */
@Module
@InstallIn(SingletonComponent::class)
object HiltAnaModule {
    @Provides @Singleton
    fun provideAnalytics(logger: SdkLogger): AnalyticsApi {
        LazyCreationTracker.mark("analytics")
        return DefaultAnalyticsService(logger)
    }
}

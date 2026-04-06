package com.grinwich.sdk.contracts

import com.grinwich.sdk.api.AnalyticsService
import javax.inject.Scope

/** Analytics services — standalone, no consumers. */
interface AnaProvisions {
    fun analytics(): AnalyticsService
}

@Scope @Retention(AnnotationRetention.RUNTIME) annotation class AnaScope

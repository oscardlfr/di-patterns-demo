package com.grinwich.sdk.feature.ana

import com.grinwich.sdk.api.AnalyticsApi
import com.grinwich.sdk.contracts.koin.CreationTracker
import com.grinwich.sdk.contracts.koin.KoinFeatureProvider
import org.koin.dsl.module

class AnaKoinProvider : KoinFeatureProvider("analytics") {
    override val services = setOf(AnalyticsApi::class.java)
    override fun module() = module {
        single<AnalyticsApi> { get<CreationTracker>().mark("analytics"); DefaultAnalyticsService(get()) }
    }
}

package com.grinwich.sdk.feature.observability

import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.koin.KoinFeatureProvider
import org.koin.dsl.module

/**
 * Koin Observability provider. Discovered by L/M via `ServiceLoader`
 * (entry in `META-INF/services/com.grinwich.sdk.contracts.koin.KoinFeatureProvider`).
 *
 * [persistent] = true: infra tied to the app lifecycle, not the SDK lifecycle.
 * Does NOT call `CreationTracker.mark(...)` — not a business feature, so it
 * does not count toward `builtFeatureCount` (L and N count via `.mark()`;
 * M filters by `!persistent`).
 */
class ObservabilityKoinProvider : KoinFeatureProvider("observability") {
    override val services = setOf(SdkLogger::class.java)
    override val persistent = true

    override fun module() = module {
        single<SdkLogger> { buildLogger() }
    }
}

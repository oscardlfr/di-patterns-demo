package com.grinwich.sdk.feature.observability

import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.koin.KoinFeatureProvider
import dev.whyoleg.sweetspi.ServiceProvider
import org.koin.dsl.module

/**
 * sweet-spi variant of [ObservabilityKoinProvider] for pattern N. Annotated
 * with `@ServiceProvider` so sweet-spi discovers it via KSP (without
 * manual `META-INF/services`).
 */
@ServiceProvider
object ObservabilitySweetSpiProvider : KoinFeatureProvider("observability") {
    override val services = setOf(SdkLogger::class.java)
    override val persistent = true

    override fun module() = module {
        single<SdkLogger> { buildLogger() }
    }
}

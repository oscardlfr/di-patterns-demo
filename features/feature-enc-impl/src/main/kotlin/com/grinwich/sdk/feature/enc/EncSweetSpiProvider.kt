package com.grinwich.sdk.feature.enc

import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.koin.CreationTracker
import com.grinwich.sdk.contracts.koin.KoinFeatureProvider
import dev.whyoleg.sweetspi.ServiceProvider
import org.koin.dsl.module

/** Pattern N: sweet-spi KMP-compatible discovery for Encryption feature. */
@ServiceProvider
object EncSweetSpiProvider : KoinFeatureProvider("encryption") {
    override val services = setOf(EncryptionApi::class.java, HashApi::class.java)
    // SdkLogger comes from ObservabilitySweetSpiProvider (feature-observability-impl).
    override val requiredServices = setOf(SdkLogger::class.java)
    override fun module() = module {
        single<EncryptionApi> { get<CreationTracker>().mark("encryption"); DefaultEncryptionService(get()) }
        single<HashApi> { get<CreationTracker>().mark("encryption"); DefaultHashService() }
    }
}

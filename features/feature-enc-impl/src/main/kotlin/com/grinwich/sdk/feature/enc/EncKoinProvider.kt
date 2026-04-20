package com.grinwich.sdk.feature.enc

import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.koin.CreationTracker
import com.grinwich.sdk.contracts.koin.KoinFeatureProvider
import org.koin.dsl.module

class EncKoinProvider : KoinFeatureProvider("encryption") {
    override val services = setOf(EncryptionApi::class.java, HashApi::class.java)
    // SdkLogger comes from ObservabilityKoinProvider (feature-observability-impl).
    // M (lazy) uses this to load Observability before Enc in the cascade.
    override val requiredServices = setOf(SdkLogger::class.java)
    override fun module() = module {
        single<EncryptionApi> { get<CreationTracker>().mark("encryption"); DefaultEncryptionService(get()) }
        single<HashApi> { get<CreationTracker>().mark("encryption"); DefaultHashService() }
    }
}

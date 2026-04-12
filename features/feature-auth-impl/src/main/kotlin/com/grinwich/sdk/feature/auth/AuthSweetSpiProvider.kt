package com.grinwich.sdk.feature.auth

import com.grinwich.sdk.api.AuthApi
import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.contracts.koin.CreationTracker
import com.grinwich.sdk.contracts.koin.KoinFeatureProvider
import dev.whyoleg.sweetspi.ServiceProvider
import org.koin.dsl.module

/** Pattern N: sweet-spi KMP-compatible discovery for Auth feature. */
@ServiceProvider
object AuthSweetSpiProvider : KoinFeatureProvider("auth") {
    override val services = setOf(AuthApi::class.java)
    override val requiredServices = setOf(EncryptionApi::class.java)
    override fun module() = module {
        single<AuthApi> { get<CreationTracker>().mark("auth"); DefaultAuthService(get(), get()) }
    }
}

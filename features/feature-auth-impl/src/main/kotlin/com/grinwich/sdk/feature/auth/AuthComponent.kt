package com.grinwich.sdk.feature.auth

import com.grinwich.sdk.api.AuthApi
import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.AuthScope
import dagger.Binds
import dagger.BindsInstance
import dagger.Component
import dagger.Module

/**
 * Factory: builds [AuthApi] directly — the feature is single-service, so it
 * does not need a Bundle. Public for wirings G / sdk-wiring baseline.
 */
fun buildAuthService(encryption: EncryptionApi, logger: SdkLogger): AuthApi =
    DaggerAuthComponent.builder()
        .encryption(encryption)
        .logger(logger)
        .build()
        .auth()

/**
 * AuthComponent — single-service Dagger component.
 *
 * Does not declare `dependencies = [...]`. Everything enters via `@BindsInstance`
 * in the builder: `EncryptionApi` (cross-feature) and `SdkLogger` (infra).
 */
@AuthScope
@Component(modules = [AuthModule::class])
internal interface AuthComponent {

    fun auth(): AuthApi

    @Component.Builder interface Builder {
        @BindsInstance fun encryption(encryption: EncryptionApi): Builder
        @BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): AuthComponent
    }
}

@Module
internal abstract class AuthModule {
    @Binds @AuthScope
    abstract fun auth(impl: DefaultAuthService): AuthApi
}

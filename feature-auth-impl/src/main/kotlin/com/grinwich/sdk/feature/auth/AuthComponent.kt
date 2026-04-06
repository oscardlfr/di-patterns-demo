package com.grinwich.sdk.feature.auth

import com.grinwich.sdk.api.AuthService
import com.grinwich.sdk.api.EncryptionService
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.AuthProvisions
import com.grinwich.sdk.contracts.AuthScope
import com.grinwich.sdk.contracts.CoreProvisions
import com.grinwich.sdk.contracts.EncProvisions
import dagger.Component
import dagger.Module
import dagger.Provides

/**
 * AuthComponent — cross-feature dependency via provision interfaces.
 *
 * dependencies = [CoreProvisions, EncProvisions]:
 *   - CoreProvisions.logger() → Dagger can inject SdkLogger
 *   - EncProvisions.encryption() → Dagger can inject EncryptionService
 *
 * This module NEVER imports EncComponent or CoreComponent.
 * It only knows contracts (provision interfaces) from di-contracts.
 */
@AuthScope
@Component(
    dependencies = [CoreProvisions::class, EncProvisions::class],
    modules = [AuthModule::class],
)
interface AuthComponent : AuthProvisions {

    override fun auth(): AuthService

    @Component.Builder interface Builder {
        fun core(core: CoreProvisions): Builder
        fun enc(enc: EncProvisions): Builder
        fun build(): AuthComponent
    }
}

@Module
internal class AuthModule {
    @Provides @AuthScope
    fun auth(enc: EncryptionService, logger: SdkLogger): AuthService =
        DefaultAuthService(enc, logger)
}

package com.grinwich.sdk.feature.auth

import com.grinwich.sdk.api.AuthApi
import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.AuthProvisions
import com.grinwich.sdk.contracts.AuthScope
import com.grinwich.sdk.contracts.CoreProvisions
import com.grinwich.sdk.contracts.EncProvisions
import dagger.Binds
import dagger.BindsInstance
import dagger.Component
import dagger.Module

/** Factory: builds AuthProvisions without exposing DaggerAuthComponent. */
fun buildAuthProvisions(core: CoreProvisions, logger: SdkLogger, enc: EncProvisions): AuthProvisions =
    DaggerAuthComponent.builder().core(core).logger(logger).enc(enc).build()

/**
 * AuthComponent — cross-feature dependency via provision interfaces.
 *
 * dependencies = [CoreProvisions, EncProvisions]:
 *   - CoreProvisions.logger() → Dagger can inject SdkLogger
 *   - EncProvisions.encryption() → Dagger can inject EncryptionApi
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

    override fun auth(): AuthApi

    @Component.Builder interface Builder {
        fun core(core: CoreProvisions): Builder
        @BindsInstance fun logger(logger: SdkLogger): Builder
        fun enc(enc: EncProvisions): Builder
        fun build(): AuthComponent
    }
}

@Module
internal abstract class AuthModule {
    @Binds @AuthScope
    abstract fun auth(impl: DefaultAuthService): AuthApi
}

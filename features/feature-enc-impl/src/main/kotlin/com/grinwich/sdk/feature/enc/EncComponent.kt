package com.grinwich.sdk.feature.enc

import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.EncScope
import dagger.Binds
import dagger.BindsInstance
import dagger.Component
import dagger.Module

/** Factory: builds the [EncBundle] without exposing [DaggerEncComponent] to the wiring. */
fun buildEncBundle(logger: SdkLogger): EncBundle =
    DaggerEncComponent.builder().logger(logger).build()

/**
 * EncComponent — Dagger component implementing [EncBundle] (internal handle).
 *
 * Does not declare `dependencies = [...]`: receives `SdkLogger` via `@BindsInstance`.
 * Everything it needs is passed from the provider in `build()`.
 */
@EncScope
@Component(modules = [EncModule::class])
internal interface EncComponent : EncBundle {

    override fun encryption(): EncryptionApi
    override fun hash(): HashApi

    @Component.Builder interface Builder {
        @BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): EncComponent
    }
}

@Module
internal abstract class EncModule {
    @Binds @EncScope
    abstract fun encryption(impl: DefaultEncryptionService): EncryptionApi

    @Binds @EncScope
    abstract fun hash(impl: DefaultHashService): HashApi
}

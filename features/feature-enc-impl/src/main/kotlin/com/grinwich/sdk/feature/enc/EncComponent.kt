package com.grinwich.sdk.feature.enc

import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.contracts.CoreProvisions
import com.grinwich.sdk.contracts.EncProvisions
import com.grinwich.sdk.contracts.EncScope
import dagger.Binds
import dagger.BindsInstance
import dagger.Component
import dagger.Module

/** Factory: builds EncProvisions without exposing DaggerEncComponent. */
fun buildEncProvisions(core: CoreProvisions, logger: SdkLogger): EncProvisions =
    DaggerEncComponent.builder().core(core).logger(logger).build()

/**
 * EncComponent depends on CoreProvisions (contract), NOT CoreComponent (impl).
 *
 * Dagger sees CoreProvisions.logger() and CoreProvisions.config() as provision
 * methods — it can inject SdkLogger and SdkConfig into this component's modules.
 *
 * At runtime, sdk-wiring passes a CoreComponent instance (which implements
 * CoreProvisions) to the builder. This module never imports CoreComponent.
 */
@EncScope
@Component(
    dependencies = [CoreProvisions::class],
    modules = [EncModule::class],
)
interface EncComponent : EncProvisions {

    override fun encryption(): EncryptionApi
    override fun hash(): HashApi

    @Component.Builder interface Builder {
        fun core(core: CoreProvisions): Builder
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

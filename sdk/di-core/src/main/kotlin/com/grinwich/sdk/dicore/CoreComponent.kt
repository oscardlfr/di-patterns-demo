package com.grinwich.sdk.dicore

import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.api.SdkLogger
import dagger.Component
import javax.inject.Singleton

/**
 * Shared CoreComponent for multi-module Dagger setup (Pattern F).
 *
 * Lives in a separate module (:sdk:di-core) to break the circular dependency:
 *   feature-impl --> di-core (for CoreComponent)
 *   sdk-facade  --> feature-impl (for feature Components)
 *
 * Without this module, features would need to depend on the SDK facade,
 * which depends on features --> cycle.
 *
 * No @Module needed — config and logger bound via @BindsInstance.
 * Child components access these via `dependencies = [CoreComponent::class]`.
 */
@Singleton
@Component
interface CoreComponent {
    fun logger(): SdkLogger
    fun config(): SdkConfig

    @Component.Builder interface Builder {
        @dagger.BindsInstance fun config(config: SdkConfig): Builder
        @dagger.BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): CoreComponent
    }
}

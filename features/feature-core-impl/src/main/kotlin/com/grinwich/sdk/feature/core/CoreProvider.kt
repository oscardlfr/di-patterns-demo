package com.grinwich.sdk.feature.core

import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver

/**
 * Core provider with flavor [Flavor.DAGGER].
 *
 * Patterns using the [Resolver] (H/I/J/K) receive `SdkConfig` directly via
 * the `SyntheticFeatureProvider` registered by the wiring; this provider
 * therefore does **not** publish `SdkConfig` (publishing it here would
 * collide with the synthetic and trigger a `ServiceOverrideException` at
 * registration time, or — historically — a silent self-cycle when the
 * last-write-wins overwrite landed on this provider).
 *
 * Patterns E/E2 do not consume `FeatureProvider` instances; they declare
 * their own `ServiceEntry`/`AutoServiceEntry` graph. So this Core
 * provider is intentionally a no-op for the discovery axis. Kept on the
 * classpath so wiring filters by [Flavor.DAGGER] still see *something*
 * for the core feature module — useful for diagnostics and for future
 * core-only services.
 */
class CoreProvider : FeatureProvider() {
    override val flavor = Flavor.DAGGER
    override val services: Set<Class<*>> = emptySet()

    override fun build(resolver: Resolver): Map<Class<*>, Any> = emptyMap()
}

package com.grinwich.sdk.contracts

/**
 * Pattern J: marker for providers that use kotlin-inject internally.
 *
 * Extends [FeatureProvider] so the [Resolver] works identically.
 * Discovered via `ServiceLoader.load(KIFeatureProvider::class.java)` —
 * separate from Dagger-based [FeatureProvider] registrations used by Pattern H.
 */
abstract class KIFeatureProvider<P : Any>(provisionClass: Class<P>) : FeatureProvider<P>(provisionClass)

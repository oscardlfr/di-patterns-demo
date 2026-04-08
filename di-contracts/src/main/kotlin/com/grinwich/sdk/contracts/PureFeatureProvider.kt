package com.grinwich.sdk.contracts

/**
 * Pattern I: marker for providers that build provisions without any DI framework.
 *
 * Extends [FeatureProvider] so the [Resolver] works identically.
 * Discovered via `ServiceLoader.load(PureFeatureProvider::class.java)` —
 * separate from Dagger-based [FeatureProvider] registrations used by Pattern H.
 */
abstract class PureFeatureProvider<P : Any>(provisionClass: Class<P>) : FeatureProvider<P>(provisionClass)

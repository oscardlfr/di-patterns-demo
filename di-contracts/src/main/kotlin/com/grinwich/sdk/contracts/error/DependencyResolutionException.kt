package com.grinwich.sdk.contracts.error

/**
 * Base class for every runtime failure raised by the DI machinery in
 * `di-contracts` ([com.grinwich.sdk.contracts.Resolver],
 * [com.grinwich.sdk.contracts.ServiceRegistry],
 * [com.grinwich.sdk.contracts.AutoServiceRegistry]).
 *
 * Unchecked, like Kotlin convention. Callers that want to reach a single
 * catch block can target this class; the concrete subclasses carry enough
 * context for diagnostics and do not need to be caught individually.
 *
 * Every other throwable that escapes the DI layer is considered a bug.
 */
abstract class DependencyResolutionException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * No provider is registered for the requested service class. Typically means
 * the feature module that contributes the service is missing from the
 * classpath, its `META-INF/services` entry is absent, or the wiring filtered
 * it out (e.g. flavor mismatch).
 */
class NoProviderFoundException(serviceName: String) : DependencyResolutionException(
    "No provider registered for service `$serviceName`."
)

/**
 * A provider was reached while it was already being built, i.e. its
 * `build()` transitively requested one of its own services. The resolver
 * short-circuits before the JVM stack overflows so the cycle is reported
 * as a domain error instead of a fatal `StackOverflowError`.
 *
 * The cycle itself is not reconstructed — only the first provider that
 * closed it is named.
 */
class CircularDependencyException(providerName: String) : DependencyResolutionException(
    "Circular dependency detected while building provider `$providerName`."
)

/**
 * A provider's `build()` threw. The original failure is preserved in
 * [cause]; the DI layer adds the provider name for context.
 *
 * After this is raised, the provider is marked as failed and any further
 * attempt to resolve one of its services will throw
 * [ProviderAlreadyFailedException] until the registry is cleared and the
 * provider is re-registered.
 */
class ProviderBuildException(providerName: String, cause: Throwable) : DependencyResolutionException(
    "Provider `$providerName` failed to build.",
    cause,
)

/**
 * A provider that previously failed to build is being resolved again on the
 * same registry instance. Re-running `build()` silently could mask the
 * original failure or produce nondeterministic partial graphs, so the
 * resolver refuses and requires an explicit `clear()` before retrying.
 */
class ProviderAlreadyFailedException(providerName: String) : DependencyResolutionException(
    "Provider `$providerName` already failed to build on this registry; clear() before retrying."
)

/**
 * A service resolved to an instance whose runtime type is not assignable to
 * the requested class. Usually indicates a mismatch between the keys
 * declared in `services` and the keys of the map returned by `build()`.
 */
class ServiceCastException(serviceName: String, cause: Throwable) : DependencyResolutionException(
    "Service `$serviceName` resolved to an instance of an incompatible type.",
    cause,
)

/**
 * The provider that owns [serviceName] completed `build()` without
 * publishing it. The provider's `services` set and the keys of the map
 * returned by `build()` must agree.
 */
class ServiceNotAvailableException(
    serviceName: String,
    providerName: String,
) : DependencyResolutionException(
    "Service `$serviceName` was not published by provider `$providerName` after build()."
)

/**
 * Discovered provider rejected at registration time because its
 * fully-qualified name is not present in the SDK's approved allowlist.
 *
 * Thrown by [com.grinwich.sdk.contracts.Resolver.register] when configured
 * with a strict [com.grinwich.sdk.contracts.ProviderAllowlist] and the
 * provider's class is not on the list. Defends against compile-time
 * supply-chain injection: a malicious dependency that ships a
 * `META-INF/services/com.grinwich.sdk.contracts.FeatureProvider`
 * descriptor pointing to its own class will be rejected here, before
 * its `build()` method can run.
 */
class UnapprovedProviderException(providerFqn: String) : DependencyResolutionException(
    "Provider `$providerFqn` is not on the SDK approved allowlist."
)

/**
 * Two distinct providers attempted to register the same service class.
 * Defends against service hijacking: a malicious provider that declares
 * `services = setOf(EncryptionApi::class.java)` alongside the legitimate
 * one cannot silently overwrite the index. The first provider to
 * register keeps the slot; subsequent overrides fail loudly.
 *
 * If you intentionally need to swap a provider, call
 * [com.grinwich.sdk.contracts.Resolver.clear] before registering the
 * replacement.
 */
class ServiceOverrideException(
    serviceName: String,
    existingProviderName: String,
    attemptedProviderName: String,
) : DependencyResolutionException(
    "Service `$serviceName` is already provided by `$existingProviderName`; " +
        "`$attemptedProviderName` attempted to register it."
)

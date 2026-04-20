package com.grinwich.sdk.feature.observability

import com.grinwich.sdk.api.SdkLogger

/**
 * Factory: returns a process-scoped [SdkLogger] singleton.
 *
 * The logger is tied to the app lifecycle, not the SDK lifecycle — it must
 * survive every init/shutdown cycle so correlation ids, buffers and file
 * handles remain consistent. Every caller (`ObservabilityProvider`,
 * `ObservabilityKoinProvider`, `ObservabilitySweetSpiProvider`,
 * `observabilityAutoEntry()`, …) goes through this factory, so all wirings
 * share the same instance regardless of how many times the SDK is re-initialized.
 *
 * Observability is single-service and has no injected dependencies, so it does
 * not need Dagger — a lazy-initialised [AndroidSdkLogger] is sufficient.
 */
fun buildLogger(): SdkLogger = SharedLogger.instance

private object SharedLogger {
    val instance: SdkLogger by lazy { AndroidSdkLogger() }
}

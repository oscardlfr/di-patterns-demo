package com.grinwich.sdk.feature.enc

import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi

/**
 * Multi-service handle for the Encryption feature.
 *
 * The feature exposes TWO services (`EncryptionApi`, `HashApi`) originating
 * from the SAME `DaggerEncComponent`. This bundle allows building the Component
 * once and extracting both services without two constructions.
 *
 * Public so that wirings that do NOT go through `Resolver` (G, sdk-wiring
 * baseline) can cache the handle directly instead of querying a
 * `Map<Class<*>, Any>`. Replaces the old global `EncProvisions` from
 * `di-contracts` — now lives in the feature, not in global contracts.
 */
interface EncBundle {
    fun encryption(): EncryptionApi
    fun hash(): HashApi
}

package com.grinwich.sdk.api

import android.content.Context

/**
 * Shared infrastructure -- logger, config.
 * In per-feature approaches (B, C) this is the bridge between isolated Components.
 * In single-graph approaches (A, Koin) this exists only as a convenience.
 */
interface CoreApis {
    val config: SdkConfig
    val logger: SdkLogger
}

/**
 * Uniform consumer API for all lazy multi-module SDK patterns.
 *
 * Every lazy pattern (D, E2, G, H, I, J, K, L, M, N, O, P) implements this interface.
 * Eager patterns (E, Koin) do NOT — they require explicit module selection at init.
 *
 * Consumer usage: init(context, config) → get<T>() → shutdown()
 * Enables parameterized testing with zero test duplication.
 */
interface MultiModuleSdkApi {
    val isInitialized: Boolean
    /**
     * Número de contribuciones (features) no-persistentes construidas.
     * Excluye provisiones persistentes (logger, context) que sobreviven a
     * `shutdown()` por estar atadas al ciclo de vida de la app.
     */
    val builtFeatureCount: Int
    fun init(context: Context, config: SdkConfig)
    fun <T : Any> get(clazz: Class<T>): T
    fun shutdown()
}

/** Reified convenience — can't be in the interface (inline). */
inline fun <reified T : Any> MultiModuleSdkApi.get(): T = get(T::class.java)

// All other types re-exported via api() dependencies in build.gradle.kts:
// - SdkConfig                        → from :sdk:feature-core-api
// - SdkLogger, AndroidSdkLogger      → from :sdk:observability-api
// - EncryptionApi, HashApi    → from :sdk:feature-enc-api
// - AuthApi, AuthToken            → from :sdk:feature-auth-api
// - StorageApi              → from :sdk:feature-stor-api
// - AnalyticsApi                  → from :sdk:feature-ana-api
// - SyncApi, SyncResult           → from :sdk:feature-syn-api

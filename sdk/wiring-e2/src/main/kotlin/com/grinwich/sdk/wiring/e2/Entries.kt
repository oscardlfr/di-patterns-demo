package com.grinwich.sdk.wiring.e2

import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.AutoServiceEntry
import com.grinwich.sdk.feature.ana.AnaFeatureId
import com.grinwich.sdk.feature.ana.buildAnalyticsService
import com.grinwich.sdk.feature.auth.AuthFeatureId
import com.grinwich.sdk.feature.auth.buildAuthService
import com.grinwich.sdk.feature.core.CoreFeatureId
import com.grinwich.sdk.feature.enc.EncFeatureId
import com.grinwich.sdk.feature.enc.buildEncBundle
import com.grinwich.sdk.feature.observability.ObservabilityFeatureId
import com.grinwich.sdk.feature.observability.buildLogger
import com.grinwich.sdk.feature.stor.StorFeatureId
import com.grinwich.sdk.feature.stor.buildStorageService
import com.grinwich.sdk.feature.syn.SynFeatureId
import com.grinwich.sdk.feature.syn.buildSyncService

/**
 * AutoServiceEntry definitions for Pattern E2 (lazy, DFS).
 *
 * Same entries as E, but using `AutoServiceEntry`, which declares
 * `serviceClasses` up front for indexing. Entries are installed at `init`
 * but BUILT only when the first `get<T>()` requires them.
 *
 * Replaces the legacy `AutoProvisionEntry<P>` that keyed by `Provisions`.
 */

internal fun observabilityAutoEntry() = AutoServiceEntry(
    featureId = ObservabilityFeatureId::class.java,
    serviceClasses = setOf(SdkLogger::class.java),
    persistent = true,
    build = { mapOf(SdkLogger::class.java to buildLogger()) },
)

internal fun coreAutoEntry(config: SdkConfig, context: android.content.Context) = AutoServiceEntry(
    featureId = CoreFeatureId::class.java,
    serviceClasses = setOf(SdkConfig::class.java, android.content.Context::class.java),
    build = {
        mapOf(
            SdkConfig::class.java to config,
            android.content.Context::class.java to context.applicationContext,
        )
    },
)

internal fun encAutoEntry() = AutoServiceEntry(
    featureId = EncFeatureId::class.java,
    dependencies = setOf(ObservabilityFeatureId::class.java),
    serviceClasses = setOf(EncryptionApi::class.java, HashApi::class.java),
    build = { registry ->
        val bundle = buildEncBundle(registry.get(SdkLogger::class.java))
        mapOf(
            EncryptionApi::class.java to bundle.encryption(),
            HashApi::class.java to bundle.hash(),
        )
    },
)

internal fun authAutoEntry() = AutoServiceEntry(
    featureId = AuthFeatureId::class.java,
    dependencies = setOf(ObservabilityFeatureId::class.java, EncFeatureId::class.java),
    serviceClasses = setOf(AuthApi::class.java),
    build = { registry ->
        val auth = buildAuthService(
            encryption = registry.get(EncryptionApi::class.java),
            logger = registry.get(SdkLogger::class.java),
        )
        mapOf(AuthApi::class.java to auth)
    },
)

internal fun storAutoEntry() = AutoServiceEntry(
    featureId = StorFeatureId::class.java,
    dependencies = setOf(
        ObservabilityFeatureId::class.java,
        CoreFeatureId::class.java,
        EncFeatureId::class.java,
    ),
    serviceClasses = setOf(StorageApi::class.java),
    build = { registry ->
        val storage = buildStorageService(
            context = registry.get(android.content.Context::class.java),
            config = registry.get(SdkConfig::class.java),
            encryption = registry.get(EncryptionApi::class.java),
            hash = registry.get(HashApi::class.java),
            logger = registry.get(SdkLogger::class.java),
        )
        mapOf(StorageApi::class.java to storage)
    },
)

internal fun anaAutoEntry() = AutoServiceEntry(
    featureId = AnaFeatureId::class.java,
    dependencies = setOf(ObservabilityFeatureId::class.java),
    serviceClasses = setOf(AnalyticsApi::class.java),
    build = { registry ->
        val ana = buildAnalyticsService(registry.get(SdkLogger::class.java))
        mapOf(AnalyticsApi::class.java to ana)
    },
)

internal fun synAutoEntry() = AutoServiceEntry(
    featureId = SynFeatureId::class.java,
    dependencies = setOf(
        ObservabilityFeatureId::class.java,
        EncFeatureId::class.java,
        AuthFeatureId::class.java,
        StorFeatureId::class.java,
    ),
    serviceClasses = setOf(SyncApi::class.java),
    build = { registry ->
        val sync = buildSyncService(
            auth = registry.get(AuthApi::class.java),
            storage = registry.get(StorageApi::class.java),
            encryption = registry.get(EncryptionApi::class.java),
            logger = registry.get(SdkLogger::class.java),
        )
        mapOf(SyncApi::class.java to sync)
    },
)

internal fun allAutoEntries(context: android.content.Context, config: SdkConfig) = listOf(
    observabilityAutoEntry(),
    coreAutoEntry(config, context),
    encAutoEntry(),
    authAutoEntry(),
    storAutoEntry(),
    anaAutoEntry(),
    synAutoEntry(),
)

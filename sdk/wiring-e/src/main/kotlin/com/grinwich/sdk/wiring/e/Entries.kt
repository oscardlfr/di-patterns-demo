package com.grinwich.sdk.wiring.e

import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.ServiceEntry
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
 * Entry definitions for Pattern E (ServiceRegistry with topological sort).
 *
 * Each [ServiceEntry] identifies its feature via a neutral marker class
 * (`XxxFeatureId::class.java`) and declares its dependencies as other markers.
 * The `build` lambda returns `Map<Class<*>, Any>` (service classes → instances),
 * which the registry indexes automatically.
 *
 * Replaces the legacy `ProvisionEntry<P>` that keyed by global `Provisions`.
 */

internal fun observabilityEntry() = ServiceEntry(
    featureId = ObservabilityFeatureId::class.java,
    build = { mapOf(SdkLogger::class.java to buildLogger()) },
)

internal fun coreEntry(config: SdkConfig, context: android.content.Context) = ServiceEntry(
    featureId = CoreFeatureId::class.java,
    build = {
        mapOf(
            SdkConfig::class.java to config,
            android.content.Context::class.java to context.applicationContext,
        )
    },
)

internal fun encEntry() = ServiceEntry(
    featureId = EncFeatureId::class.java,
    dependencies = setOf(ObservabilityFeatureId::class.java),
    build = { registry ->
        val bundle = buildEncBundle(registry.get(SdkLogger::class.java))
        mapOf(
            EncryptionApi::class.java to bundle.encryption(),
            HashApi::class.java to bundle.hash(),
        )
    },
)

internal fun authEntry() = ServiceEntry(
    featureId = AuthFeatureId::class.java,
    dependencies = setOf(ObservabilityFeatureId::class.java, EncFeatureId::class.java),
    build = { registry ->
        val auth = buildAuthService(
            encryption = registry.get(EncryptionApi::class.java),
            logger = registry.get(SdkLogger::class.java),
        )
        mapOf(AuthApi::class.java to auth)
    },
)

internal fun storEntry() = ServiceEntry(
    featureId = StorFeatureId::class.java,
    dependencies = setOf(
        ObservabilityFeatureId::class.java,
        CoreFeatureId::class.java,
        EncFeatureId::class.java,
    ),
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

internal fun anaEntry() = ServiceEntry(
    featureId = AnaFeatureId::class.java,
    dependencies = setOf(ObservabilityFeatureId::class.java),
    build = { registry ->
        val ana = buildAnalyticsService(registry.get(SdkLogger::class.java))
        mapOf(AnalyticsApi::class.java to ana)
    },
)

internal fun synEntry() = ServiceEntry(
    featureId = SynFeatureId::class.java,
    dependencies = setOf(
        ObservabilityFeatureId::class.java,
        EncFeatureId::class.java,
        AuthFeatureId::class.java,
        StorFeatureId::class.java,
    ),
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

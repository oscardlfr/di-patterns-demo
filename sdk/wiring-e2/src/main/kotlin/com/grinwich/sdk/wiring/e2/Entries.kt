package com.grinwich.sdk.wiring.e2

import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.*
import com.grinwich.sdk.feature.ana.DaggerAnaComponent
import com.grinwich.sdk.feature.auth.DaggerAuthComponent
import com.grinwich.sdk.feature.core.DaggerCoreComponent
import com.grinwich.sdk.feature.enc.DaggerEncComponent
import com.grinwich.sdk.feature.stor.DaggerStorComponent
import com.grinwich.sdk.feature.syn.DaggerSynComponent

/**
 * AutoProvisionEntry definitions for multi-module Pattern E2.
 *
 * Same entries as wiring-e but using AutoProvisionEntry with serviceClasses
 * for service->provision indexing. Enables get<T>() auto-discovery.
 */

internal fun coreAutoEntry(config: SdkConfig, logger: SdkLogger) = AutoProvisionEntry(
    provisionClass = CoreProvisions::class.java,
    serviceClasses = setOf(SdkConfig::class.java, SdkLogger::class.java),
    build = {
        DaggerCoreComponent.builder().config(config).logger(logger).build()
    },
    services = { prov ->
        mapOf(
            SdkConfig::class.java to prov.config(),
            SdkLogger::class.java to prov.logger(),
        )
    },
)

internal val encAutoEntry = AutoProvisionEntry(
    provisionClass = EncProvisions::class.java,
    dependencies = setOf(CoreProvisions::class.java),
    serviceClasses = setOf(EncryptionApi::class.java, HashApi::class.java),
    build = { registry ->
        DaggerEncComponent.builder()
            .core(registry.provision(CoreProvisions::class.java))
            .build()
    },
    services = { prov ->
        mapOf(
            EncryptionApi::class.java to prov.encryption(),
            HashApi::class.java to prov.hash(),
        )
    },
)

internal val authAutoEntry = AutoProvisionEntry(
    provisionClass = AuthProvisions::class.java,
    dependencies = setOf(CoreProvisions::class.java, EncProvisions::class.java),
    serviceClasses = setOf(AuthApi::class.java),
    build = { registry ->
        DaggerAuthComponent.builder()
            .core(registry.provision(CoreProvisions::class.java))
            .enc(registry.provision(EncProvisions::class.java))
            .build()
    },
    services = { prov ->
        mapOf(AuthApi::class.java to prov.auth())
    },
)

internal val storAutoEntry = AutoProvisionEntry(
    provisionClass = StorProvisions::class.java,
    dependencies = setOf(CoreProvisions::class.java, EncProvisions::class.java),
    serviceClasses = setOf(StorageApi::class.java),
    build = { registry ->
        DaggerStorComponent.builder()
            .core(registry.provision(CoreProvisions::class.java))
            .enc(registry.provision(EncProvisions::class.java))
            .build()
    },
    services = { prov ->
        mapOf(StorageApi::class.java to prov.storage())
    },
)

internal val anaAutoEntry = AutoProvisionEntry(
    provisionClass = AnaProvisions::class.java,
    dependencies = setOf(CoreProvisions::class.java),
    serviceClasses = setOf(AnalyticsApi::class.java),
    build = { registry ->
        DaggerAnaComponent.builder()
            .core(registry.provision(CoreProvisions::class.java))
            .build()
    },
    services = { prov ->
        mapOf(AnalyticsApi::class.java to prov.analytics())
    },
)

internal val synAutoEntry = AutoProvisionEntry(
    provisionClass = SynProvisions::class.java,
    dependencies = setOf(
        CoreProvisions::class.java,
        EncProvisions::class.java,
        AuthProvisions::class.java,
        StorProvisions::class.java,
    ),
    serviceClasses = setOf(SyncApi::class.java),
    build = { registry ->
        DaggerSynComponent.builder()
            .core(registry.provision(CoreProvisions::class.java))
            .enc(registry.provision(EncProvisions::class.java))
            .auth(registry.provision(AuthProvisions::class.java))
            .storage(registry.provision(StorProvisions::class.java))
            .build()
    },
    services = { prov ->
        mapOf(SyncApi::class.java to prov.sync())
    },
)

internal fun allAutoEntries(config: SdkConfig, logger: SdkLogger) = listOf(
    coreAutoEntry(config, logger),
    encAutoEntry,
    authAutoEntry,
    storAutoEntry,
    anaAutoEntry,
    synAutoEntry,
)

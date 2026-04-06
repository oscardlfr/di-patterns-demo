package com.grinwich.sdk.wiring.e

import com.grinwich.sdk.api.*
import com.grinwich.sdk.contracts.*
import com.grinwich.sdk.feature.ana.DaggerAnaComponent
import com.grinwich.sdk.feature.auth.DaggerAuthComponent
import com.grinwich.sdk.feature.core.DaggerCoreComponent
import com.grinwich.sdk.feature.enc.DaggerEncComponent
import com.grinwich.sdk.feature.stor.DaggerStorComponent
import com.grinwich.sdk.feature.syn.DaggerSynComponent

/**
 * ProvisionEntry definitions for multi-module Pattern E.
 *
 * Each entry maps a provision interface to its Dagger component builder.
 * This is the ONLY place that imports DaggerXxxComponent classes.
 */

internal fun coreEntry(config: SdkConfig, logger: SdkLogger) = ProvisionEntry(
    provisionClass = CoreProvisions::class.java,
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

internal val encEntry = ProvisionEntry(
    provisionClass = EncProvisions::class.java,
    dependencies = setOf(CoreProvisions::class.java),
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

internal val authEntry = ProvisionEntry(
    provisionClass = AuthProvisions::class.java,
    dependencies = setOf(CoreProvisions::class.java, EncProvisions::class.java),
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

internal val storEntry = ProvisionEntry(
    provisionClass = StorProvisions::class.java,
    dependencies = setOf(CoreProvisions::class.java, EncProvisions::class.java),
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

internal val anaEntry = ProvisionEntry(
    provisionClass = AnaProvisions::class.java,
    dependencies = setOf(CoreProvisions::class.java),
    build = { registry ->
        DaggerAnaComponent.builder()
            .core(registry.provision(CoreProvisions::class.java))
            .build()
    },
    services = { prov ->
        mapOf(AnalyticsApi::class.java to prov.analytics())
    },
)

internal val synEntry = ProvisionEntry(
    provisionClass = SynProvisions::class.java,
    dependencies = setOf(
        CoreProvisions::class.java,
        EncProvisions::class.java,
        AuthProvisions::class.java,
        StorProvisions::class.java,
    ),
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

internal fun allEntries(config: SdkConfig, logger: SdkLogger) = listOf(
    coreEntry(config, logger),
    encEntry,
    authEntry,
    storEntry,
    anaEntry,
    synEntry,
)

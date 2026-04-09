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
        DaggerCoreComponent.builder().config(config).build()
    },
    services = { prov ->
        mapOf(
            SdkConfig::class.java to prov.config(),
            SdkLogger::class.java to logger,
        )
    },
)

internal fun encAutoEntry(logger: SdkLogger) = AutoProvisionEntry(
    provisionClass = EncProvisions::class.java,
    dependencies = setOf(CoreProvisions::class.java),
    serviceClasses = setOf(EncryptionApi::class.java, HashApi::class.java),
    build = { registry ->
        DaggerEncComponent.builder()
            .core(registry.provision(CoreProvisions::class.java))
            .logger(logger)
            .build()
    },
    services = { prov ->
        mapOf(
            EncryptionApi::class.java to prov.encryption(),
            HashApi::class.java to prov.hash(),
        )
    },
)

internal fun authAutoEntry(logger: SdkLogger) = AutoProvisionEntry(
    provisionClass = AuthProvisions::class.java,
    dependencies = setOf(CoreProvisions::class.java, EncProvisions::class.java),
    serviceClasses = setOf(AuthApi::class.java),
    build = { registry ->
        DaggerAuthComponent.builder()
            .core(registry.provision(CoreProvisions::class.java))
            .logger(logger)
            .enc(registry.provision(EncProvisions::class.java))
            .build()
    },
    services = { prov ->
        mapOf(AuthApi::class.java to prov.auth())
    },
)

internal fun storAutoEntry(logger: SdkLogger) = AutoProvisionEntry(
    provisionClass = StorProvisions::class.java,
    dependencies = setOf(CoreProvisions::class.java, EncProvisions::class.java, ContextProvisions::class.java),
    serviceClasses = setOf(StorageApi::class.java),
    build = { registry ->
        DaggerStorComponent.builder()
            .core(registry.provision(CoreProvisions::class.java))
            .logger(logger)
            .enc(registry.provision(EncProvisions::class.java))
            .ctx(registry.provision(ContextProvisions::class.java))
            .build()
    },
    services = { prov ->
        mapOf(StorageApi::class.java to prov.storage())
    },
)

internal fun anaAutoEntry(logger: SdkLogger) = AutoProvisionEntry(
    provisionClass = AnaProvisions::class.java,
    dependencies = setOf(CoreProvisions::class.java),
    serviceClasses = setOf(AnalyticsApi::class.java),
    build = { registry ->
        DaggerAnaComponent.builder()
            .core(registry.provision(CoreProvisions::class.java))
            .logger(logger)
            .build()
    },
    services = { prov ->
        mapOf(AnalyticsApi::class.java to prov.analytics())
    },
)

internal fun synAutoEntry(logger: SdkLogger) = AutoProvisionEntry(
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
            .logger(logger)
            .enc(registry.provision(EncProvisions::class.java))
            .auth(registry.provision(AuthProvisions::class.java))
            .storage(registry.provision(StorProvisions::class.java))
            .build()
    },
    services = { prov ->
        mapOf(SyncApi::class.java to prov.sync())
    },
)

internal fun ctxAutoEntry(context: android.content.Context) = AutoProvisionEntry(
    provisionClass = ContextProvisions::class.java,
    serviceClasses = setOf(android.content.Context::class.java),
    build = {
        val appCtx = context.applicationContext
        object : ContextProvisions {
            override fun appContext() = appCtx
        }
    },
    services = { prov ->
        mapOf(android.content.Context::class.java to prov.appContext())
    },
)

internal fun allAutoEntries(context: android.content.Context, config: SdkConfig, logger: SdkLogger) = listOf(
    ctxAutoEntry(context),
    coreAutoEntry(config, logger),
    encAutoEntry(logger),
    authAutoEntry(logger),
    storAutoEntry(logger),
    anaAutoEntry(logger),
    synAutoEntry(logger),
)

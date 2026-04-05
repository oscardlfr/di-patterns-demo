package com.grinwich.benchmark.daggere2

import com.grinwich.sdk.api.*
import com.grinwich.sdk.common.*
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/**
 * Approach E2: Auto-Init Registry — entries installed lazily, built on demand.
 *
 * Benchmark-specific copy with E2-prefixed names to avoid classpath collisions.
 * Structurally identical to E benchmark components, but uses BenchAutoRegistry
 * which builds components on-demand via get<T>() instead of eagerly.
 */

// ============================================================
// Lightweight auto-registry for benchmarks
// ============================================================

interface BenchAutoComponent

class BenchAutoEntry<C : BenchAutoComponent>(
    val componentClass: Class<C>,
    val dependencies: Set<Class<out BenchAutoComponent>> = emptySet(),
    val serviceClasses: Set<Class<*>>,
    val build: (BenchAutoRegistry) -> C,
    val services: (C) -> Map<Class<*>, Any>,
)

class BenchAutoRegistry {
    private val catalog = HashMap<Class<out BenchAutoComponent>, BenchAutoEntry<*>>()
    private val serviceIndex = HashMap<Class<*>, Class<out BenchAutoComponent>>()
    val components = HashMap<Class<out BenchAutoComponent>, BenchAutoComponent>()
    val services = HashMap<Class<*>, Any>()

    fun install(entry: BenchAutoEntry<*>) {
        catalog[entry.componentClass] = entry
        for (svc in entry.serviceClasses) {
            serviceIndex[svc] = entry.componentClass
        }
    }

    fun installAll(entries: List<BenchAutoEntry<*>>) {
        for (entry in entries) install(entry)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(clazz: Class<T>): T {
        services[clazz]?.let { return it as T }
        val componentClass = serviceIndex[clazz]
            ?: error("No entry provides ${clazz.simpleName}")
        ensureBuilt(componentClass)
        return services[clazz] as T
    }

    @Suppress("UNCHECKED_CAST")
    fun <C : BenchAutoComponent> component(clazz: Class<C>): C = components[clazz] as C

    fun clear() { catalog.clear(); serviceIndex.clear(); components.clear(); services.clear() }
    fun clearBuilt() { components.clear(); services.clear() }

    private fun ensureBuilt(componentClass: Class<out BenchAutoComponent>) {
        if (components.containsKey(componentClass)) return
        val entry = catalog[componentClass] ?: error("Not installed: ${componentClass.simpleName}")
        for (dep in entry.dependencies) ensureBuilt(dep)
        @Suppress("UNCHECKED_CAST")
        val typed = entry as BenchAutoEntry<BenchAutoComponent>
        val component = typed.build(this)
        components[entry.componentClass] = component
        services.putAll(typed.services(component))
    }
}

// ============================================================
// Dagger components — same hierarchy as E benchmark
// ============================================================

// --- Core ---
@Singleton @Component(modules = [E2CoreModule::class])
interface E2CoreComponent : BenchAutoComponent {
    fun logger(): SdkLogger
    fun config(): SdkConfig
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun config(config: SdkConfig): Builder
        @dagger.BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): E2CoreComponent
    }
}
@Module class E2CoreModule {
    @Provides @Singleton fun core(config: SdkConfig, logger: SdkLogger): CoreApis = CoreApisImpl(config, logger)
}

// --- Encryption ---
@E2EncScope @Component(dependencies = [E2CoreComponent::class], modules = [E2EncModule::class])
interface E2EncComponent : BenchAutoComponent {
    fun encryption(): EncryptionService
    fun hash(): HashService
    @Component.Builder interface Builder {
        fun core(core: E2CoreComponent): Builder
        fun build(): E2EncComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class E2EncScope
@Module class E2EncModule {
    @Provides @E2EncScope fun enc(logger: SdkLogger): EncryptionService = DefaultEncryptionService(logger)
    @Provides @E2EncScope fun hash(): HashService = DefaultHashService()
}

// --- Auth ---
@E2AuthScope @Component(dependencies = [E2CoreComponent::class, E2EncComponent::class], modules = [E2AuthModule::class])
interface E2AuthComponent : BenchAutoComponent {
    fun auth(): AuthService
    @Component.Builder interface Builder {
        fun core(core: E2CoreComponent): Builder
        fun enc(enc: E2EncComponent): Builder
        fun build(): E2AuthComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class E2AuthScope
@Module class E2AuthModule {
    @Provides @E2AuthScope fun auth(enc: EncryptionService, logger: SdkLogger): AuthService = DefaultAuthService(enc, logger)
}

// --- Storage ---
@E2StorageScope @Component(dependencies = [E2CoreComponent::class, E2EncComponent::class], modules = [E2StorageModule::class])
interface E2StorageComponent : BenchAutoComponent {
    fun storage(): SecureStorageService
    @Component.Builder interface Builder {
        fun core(core: E2CoreComponent): Builder
        fun enc(enc: E2EncComponent): Builder
        fun build(): E2StorageComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class E2StorageScope
@Module class E2StorageModule {
    @Provides @E2StorageScope fun storage(enc: EncryptionService, hash: HashService, logger: SdkLogger): SecureStorageService =
        DefaultSecureStorageService(enc, hash, logger)
}

// --- Analytics ---
@E2AnalyticsScope @Component(dependencies = [E2CoreComponent::class], modules = [E2AnalyticsModule::class])
interface E2AnalyticsComponent : BenchAutoComponent {
    fun analytics(): AnalyticsService
    @Component.Builder interface Builder {
        fun core(core: E2CoreComponent): Builder
        fun build(): E2AnalyticsComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class E2AnalyticsScope
@Module class E2AnalyticsModule {
    @Provides @E2AnalyticsScope fun analytics(logger: SdkLogger): AnalyticsService = DefaultAnalyticsService(logger)
}

// --- Sync ---
@E2SyncScope @Component(dependencies = [E2CoreComponent::class, E2EncComponent::class, E2AuthComponent::class, E2StorageComponent::class], modules = [E2SyncModule::class])
interface E2SyncComponent : BenchAutoComponent {
    fun sync(): SyncService
    @Component.Builder interface Builder {
        fun core(core: E2CoreComponent): Builder
        fun enc(enc: E2EncComponent): Builder
        fun auth(auth: E2AuthComponent): Builder
        fun storage(storage: E2StorageComponent): Builder
        fun build(): E2SyncComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class E2SyncScope
@Module class E2SyncModule {
    @Provides @E2SyncScope fun sync(auth: AuthService, storage: SecureStorageService, enc: EncryptionService, logger: SdkLogger): SyncService =
        DefaultSyncService(auth, storage, enc, logger)
}

// ============================================================
// Feature entries with serviceClasses for auto-indexing
// ============================================================

fun e2CoreEntry(config: SdkConfig, logger: SdkLogger) = BenchAutoEntry(
    componentClass = E2CoreComponent::class.java,
    serviceClasses = setOf(SdkLogger::class.java, SdkConfig::class.java),
    build = { DaggerE2CoreComponent.builder().config(config).logger(logger).build() },
    services = { comp ->
        mapOf(
            SdkLogger::class.java to comp.logger(),
            SdkConfig::class.java to comp.config(),
        )
    },
)

val e2EncEntry = BenchAutoEntry(
    componentClass = E2EncComponent::class.java,
    dependencies = setOf(E2CoreComponent::class.java),
    serviceClasses = setOf(EncryptionService::class.java, HashService::class.java),
    build = { reg -> DaggerE2EncComponent.builder().core(reg.component(E2CoreComponent::class.java)).build() },
    services = { comp ->
        mapOf(
            EncryptionService::class.java to comp.encryption(),
            HashService::class.java to comp.hash(),
        )
    },
)

val e2AuthEntry = BenchAutoEntry(
    componentClass = E2AuthComponent::class.java,
    dependencies = setOf(E2CoreComponent::class.java, E2EncComponent::class.java),
    serviceClasses = setOf(AuthService::class.java),
    build = { reg ->
        DaggerE2AuthComponent.builder()
            .core(reg.component(E2CoreComponent::class.java))
            .enc(reg.component(E2EncComponent::class.java))
            .build()
    },
    services = { comp -> mapOf(AuthService::class.java to comp.auth()) },
)

val e2StorageEntry = BenchAutoEntry(
    componentClass = E2StorageComponent::class.java,
    dependencies = setOf(E2CoreComponent::class.java, E2EncComponent::class.java),
    serviceClasses = setOf(SecureStorageService::class.java),
    build = { reg ->
        DaggerE2StorageComponent.builder()
            .core(reg.component(E2CoreComponent::class.java))
            .enc(reg.component(E2EncComponent::class.java))
            .build()
    },
    services = { comp -> mapOf(SecureStorageService::class.java to comp.storage()) },
)

val e2AnalyticsEntry = BenchAutoEntry(
    componentClass = E2AnalyticsComponent::class.java,
    dependencies = setOf(E2CoreComponent::class.java),
    serviceClasses = setOf(AnalyticsService::class.java),
    build = { reg -> DaggerE2AnalyticsComponent.builder().core(reg.component(E2CoreComponent::class.java)).build() },
    services = { comp -> mapOf(AnalyticsService::class.java to comp.analytics()) },
)

val e2SyncEntry = BenchAutoEntry(
    componentClass = E2SyncComponent::class.java,
    dependencies = setOf(E2CoreComponent::class.java, E2EncComponent::class.java, E2AuthComponent::class.java, E2StorageComponent::class.java),
    serviceClasses = setOf(SyncService::class.java),
    build = { reg ->
        DaggerE2SyncComponent.builder()
            .core(reg.component(E2CoreComponent::class.java))
            .enc(reg.component(E2EncComponent::class.java))
            .auth(reg.component(E2AuthComponent::class.java))
            .storage(reg.component(E2StorageComponent::class.java))
            .build()
    },
    services = { comp -> mapOf(SyncService::class.java to comp.sync()) },
)

fun allE2Entries(config: SdkConfig, logger: SdkLogger) = listOf(
    e2CoreEntry(config, logger),
    e2EncEntry, e2AuthEntry, e2StorageEntry, e2AnalyticsEntry, e2SyncEntry,
)

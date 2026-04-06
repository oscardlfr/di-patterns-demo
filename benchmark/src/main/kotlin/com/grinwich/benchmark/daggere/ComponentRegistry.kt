package com.grinwich.benchmark.daggere

import com.grinwich.sdk.api.*
import com.grinwich.sdk.feature.observability.AndroidSdkLogger
import com.grinwich.sdk.common.*
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

/** Approach E: Component Registry — explicit service bindings, no reflection, eager resolution */

// ============================================================
// Lightweight registry for benchmarks (no SDK facade overhead)
// ============================================================

interface BenchDiComponent

class BenchFeatureEntry<C : BenchDiComponent>(
    val componentClass: Class<C>,
    val dependencies: Set<Class<out BenchDiComponent>> = emptySet(),
    val build: (BenchComponentRegistry) -> C,
    val services: (C) -> Map<Class<*>, Any>,
)

class BenchComponentRegistry {
    val components = HashMap<Class<out BenchDiComponent>, BenchDiComponent>()
    val services = HashMap<Class<*>, Any>()

    fun <C : BenchDiComponent> register(entry: BenchFeatureEntry<C>) {
        val component = entry.build(this)
        components[entry.componentClass] = component
        services.putAll(entry.services(component))
    }

    fun registerAll(entries: List<BenchFeatureEntry<*>>) {
        // Simple topo-sort for benchmarks
        val byClass = entries.associateBy { it.componentClass }
        val inDegree = entries.associate { it.componentClass to it.dependencies.count { d -> d in byClass } }.toMutableMap()
        val queue = ArrayDeque(entries.filter { inDegree[it.componentClass] == 0 })
        while (queue.isNotEmpty()) {
            val entry = queue.removeFirst()
            // Type-safe by construction: entry was registered as BenchFeatureEntry<C : BenchDiComponent>
            @Suppress("UNCHECKED_CAST")
            register(entry as BenchFeatureEntry<BenchDiComponent>)
            for (other in entries) {
                if (entry.componentClass in other.dependencies) {
                    val newDeg = (inDegree[other.componentClass] ?: 0) - 1
                    inDegree[other.componentClass] = newDeg
                    if (newDeg == 0) queue.add(other)
                }
            }
        }
    }

    fun <C : BenchDiComponent> component(clazz: Class<C>): C =
        clazz.cast(components[clazz]) ?: error("Component ${clazz.simpleName} not registered.")

    fun <T : Any> get(clazz: Class<T>): T =
        clazz.cast(services[clazz]) ?: error("Service ${clazz.simpleName} not available.")

    fun hasComponent(clazz: Class<out BenchDiComponent>): Boolean = components.containsKey(clazz)

    fun clear() { components.clear(); services.clear() }
}

// ============================================================
// Dagger components — same hierarchy as Pattern D benchmark
// ============================================================

val benchNoopLogger: SdkLogger = AndroidSdkLogger()

// --- Core ---
@Singleton @Component(modules = [ECoreModule::class])
interface ECoreComponent : BenchDiComponent {
    fun logger(): SdkLogger
    fun config(): SdkConfig
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun config(config: SdkConfig): Builder
        @dagger.BindsInstance fun logger(logger: SdkLogger): Builder
        fun build(): ECoreComponent
    }
}
@Module class ECoreModule {
    @Provides @Singleton fun core(config: SdkConfig, logger: SdkLogger): CoreApis = CoreApisImpl(config, logger)
}

// --- Encryption ---
@EEncScope @Component(dependencies = [ECoreComponent::class], modules = [EEncModule::class])
interface EEncComponent : BenchDiComponent {
    fun encryption(): EncryptionApi
    fun hash(): HashApi
    @Component.Builder interface Builder {
        fun core(core: ECoreComponent): Builder
        fun build(): EEncComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class EEncScope
@Module class EEncModule {
    @Provides @EEncScope fun enc(logger: SdkLogger): EncryptionApi = DefaultEncryptionService(logger)
    @Provides @EEncScope fun hash(): HashApi = DefaultHashService()
}

// --- Auth ---
@EAuthScope @Component(dependencies = [ECoreComponent::class, EEncComponent::class], modules = [EAuthModule::class])
interface EAuthComponent : BenchDiComponent {
    fun auth(): AuthApi
    @Component.Builder interface Builder {
        fun core(core: ECoreComponent): Builder
        fun enc(enc: EEncComponent): Builder
        fun build(): EAuthComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class EAuthScope
@Module class EAuthModule {
    @Provides @EAuthScope fun auth(enc: EncryptionApi, logger: SdkLogger): AuthApi = DefaultAuthService(enc, logger)
}

// --- Storage ---
@EStorageScope @Component(dependencies = [ECoreComponent::class, EEncComponent::class], modules = [EStorageModule::class])
interface EStorageComponent : BenchDiComponent {
    fun storage(): StorageApi
    @Component.Builder interface Builder {
        fun core(core: ECoreComponent): Builder
        fun enc(enc: EEncComponent): Builder
        fun build(): EStorageComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class EStorageScope
@Module class EStorageModule {
    @Provides @EStorageScope fun storage(enc: EncryptionApi, hash: HashApi, logger: SdkLogger): StorageApi =
        DefaultSecureStorageService(enc, hash, logger)
}

// --- Analytics ---
@EAnalyticsScope @Component(dependencies = [ECoreComponent::class], modules = [EAnalyticsModule::class])
interface EAnalyticsComponent : BenchDiComponent {
    fun analytics(): AnalyticsApi
    @Component.Builder interface Builder {
        fun core(core: ECoreComponent): Builder
        fun build(): EAnalyticsComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class EAnalyticsScope
@Module class EAnalyticsModule {
    @Provides @EAnalyticsScope fun analytics(logger: SdkLogger): AnalyticsApi = DefaultAnalyticsService(logger)
}

// --- Sync ---
@ESyncScope @Component(dependencies = [ECoreComponent::class, EEncComponent::class, EAuthComponent::class, EStorageComponent::class], modules = [ESyncModule::class])
interface ESyncComponent : BenchDiComponent {
    fun sync(): SyncApi
    @Component.Builder interface Builder {
        fun core(core: ECoreComponent): Builder
        fun enc(enc: EEncComponent): Builder
        fun auth(auth: EAuthComponent): Builder
        fun storage(storage: EStorageComponent): Builder
        fun build(): ESyncComponent
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) annotation class ESyncScope
@Module class ESyncModule {
    @Provides @ESyncScope fun sync(auth: AuthApi, storage: StorageApi, enc: EncryptionApi, logger: SdkLogger): SyncApi =
        DefaultSyncService(auth, storage, enc, logger)
}

// ============================================================
// Feature entries with explicit eager bindings
// ============================================================

fun eCoreEntry(config: SdkConfig, logger: SdkLogger) = BenchFeatureEntry(
    componentClass = ECoreComponent::class.java,
    build = { DaggerECoreComponent.builder().config(config).logger(logger).build() },
    services = { comp ->
        mapOf(
            SdkLogger::class.java to comp.logger(),
            SdkConfig::class.java to comp.config(),
        )
    },
)

val eEncEntry = BenchFeatureEntry(
    componentClass = EEncComponent::class.java,
    dependencies = setOf(ECoreComponent::class.java),
    build = { reg -> DaggerEEncComponent.builder().core(reg.component(ECoreComponent::class.java)).build() },
    services = { comp ->
        mapOf(
            EncryptionApi::class.java to comp.encryption(),
            HashApi::class.java to comp.hash(),
        )
    },
)

val eAuthEntry = BenchFeatureEntry(
    componentClass = EAuthComponent::class.java,
    dependencies = setOf(ECoreComponent::class.java, EEncComponent::class.java),
    build = { reg ->
        DaggerEAuthComponent.builder()
            .core(reg.component(ECoreComponent::class.java))
            .enc(reg.component(EEncComponent::class.java))
            .build()
    },
    services = { comp -> mapOf(AuthApi::class.java to comp.auth()) },
)

val eStorageEntry = BenchFeatureEntry(
    componentClass = EStorageComponent::class.java,
    dependencies = setOf(ECoreComponent::class.java, EEncComponent::class.java),
    build = { reg ->
        DaggerEStorageComponent.builder()
            .core(reg.component(ECoreComponent::class.java))
            .enc(reg.component(EEncComponent::class.java))
            .build()
    },
    services = { comp -> mapOf(StorageApi::class.java to comp.storage()) },
)

val eAnalyticsEntry = BenchFeatureEntry(
    componentClass = EAnalyticsComponent::class.java,
    dependencies = setOf(ECoreComponent::class.java),
    build = { reg -> DaggerEAnalyticsComponent.builder().core(reg.component(ECoreComponent::class.java)).build() },
    services = { comp -> mapOf(AnalyticsApi::class.java to comp.analytics()) },
)

val eSyncEntry = BenchFeatureEntry(
    componentClass = ESyncComponent::class.java,
    dependencies = setOf(ECoreComponent::class.java, EEncComponent::class.java, EAuthComponent::class.java, EStorageComponent::class.java),
    build = { reg ->
        DaggerESyncComponent.builder()
            .core(reg.component(ECoreComponent::class.java))
            .enc(reg.component(EEncComponent::class.java))
            .auth(reg.component(EAuthComponent::class.java))
            .storage(reg.component(EStorageComponent::class.java))
            .build()
    },
    services = { comp -> mapOf(SyncApi::class.java to comp.sync()) },
)

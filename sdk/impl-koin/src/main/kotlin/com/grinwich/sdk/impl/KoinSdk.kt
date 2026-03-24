package com.grinwich.sdk.impl

import com.grinwich.sdk.api.*
import com.grinwich.sdk.common.*
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

// ============================================================
// Sealed SdkModule — type-safe feature selection
// ============================================================

sealed class SdkModule(val key: String) {

    sealed class Encryption(key: String) : SdkModule(key) {
        data object Default : Encryption("encryption-default")
    }

    sealed class Auth(key: String) : SdkModule(key) {
        data object Default : Auth("auth-default")
    }

    sealed class Storage(key: String) : SdkModule(key) {
        data object Secure : Storage("storage-secure")
    }

    sealed class Analytics(key: String) : SdkModule(key) {
        data object Default : Analytics("analytics-default")
    }

    sealed class Sync(key: String) : SdkModule(key) {
        data object Default : Sync("sync-default")
    }

    val category: String
        get() = when (this) {
            is Encryption -> "encryption"
            is Auth -> "auth"
            is Storage -> "storage"
            is Analytics -> "analytics"
            is Sync -> "sync"
        }

    val registrationClassName: String
        get() = when (this) {
            is Encryption.Default -> "com.grinwich.sdk.impl.EncryptionRegistration"
            is Auth.Default -> "com.grinwich.sdk.impl.AuthRegistration"
            is Storage.Secure -> "com.grinwich.sdk.impl.StorageRegistration"
            is Analytics.Default -> "com.grinwich.sdk.impl.AnalyticsRegistration"
            is Sync.Default -> "com.grinwich.sdk.impl.SyncRegistration"
        }

    /**
     * Modules that MUST be initialized before this module can work.
     * Used by getOrInitModule() to cascade lazy initialization.
     */
    val requiredDependencies: Set<SdkModule>
        get() = when (this) {
            is Encryption.Default -> emptySet()
            is Auth.Default -> setOf(Encryption.Default)
            is Storage.Secure -> setOf(Encryption.Default)
            is Analytics.Default -> emptySet()                                    // Case 1: no deps
            is Sync.Default -> setOf(Auth.Default, Storage.Secure, Encryption.Default) // Case 2: heavy deps
        }
}

// ============================================================
// Registry
// ============================================================

interface SdkModuleRegistration {
    val module: SdkModule
    val koinModule: Module
}

object SdkModuleRegistry {
    private val _registered = mutableMapOf<String, () -> Module>()

    fun register(module: SdkModule, provider: () -> Module) {
        _registered[module.key] = provider
    }

    fun resolve(module: SdkModule): Module {
        val provider = _registered[module.key]
            ?: throw IllegalArgumentException(
                "Module '${module.key}' not registered. Add its Gradle dependency. " +
                "Available: ${_registered.keys}"
            )
        return provider()
    }
}

// ============================================================
// Per-feature registrations
// ============================================================

object EncryptionRegistration : SdkModuleRegistration {
    override val module = SdkModule.Encryption.Default
    override val koinModule = module {
        single<HashService> { DefaultHashService() }
        single<EncryptionService> { DefaultEncryptionService(get()) }
    }
    init { SdkModuleRegistry.register(module) { koinModule } }
}

object AuthRegistration : SdkModuleRegistration {
    override val module = SdkModule.Auth.Default
    override val koinModule = module {
        single<AuthService> { DefaultAuthService(get(), get()) }
    }
    init { SdkModuleRegistry.register(module) { koinModule } }
}

object StorageRegistration : SdkModuleRegistration {
    override val module = SdkModule.Storage.Secure
    override val koinModule = module {
        single<SecureStorageService> { DefaultSecureStorageService(get(), get(), get()) }
    }
    init { SdkModuleRegistry.register(module) { koinModule } }
}

object AnalyticsRegistration : SdkModuleRegistration {
    override val module = SdkModule.Analytics.Default
    override val koinModule = module {
        single<AnalyticsService> { DefaultAnalyticsService(get()) }
    }
    init { SdkModuleRegistry.register(module) { koinModule } }
}

object SyncRegistration : SdkModuleRegistration {
    override val module = SdkModule.Sync.Default
    override val koinModule = module {
        // HEAVY cross-feature: needs Auth + Storage + Encryption from same graph
        single<SyncService> { DefaultSyncService(get(), get(), get(), get()) }
    }
    init { SdkModuleRegistry.register(module) { koinModule } }
}

// ============================================================
// FoundationSingletons
// ============================================================

internal object FoundationSingletons {
    val logger: SdkLogger = AndroidSdkLogger()
}

private fun foundationModule(config: SdkConfig) = module {
    single<SdkConfig> { config }
    single<SdkLogger> { FoundationSingletons.logger }
    single<CoreApis> { CoreApisImpl(get(), get()) }
}

// ============================================================
// Discovery
// ============================================================

private fun discoverRegistrations(modules: Set<SdkModule>) {
    for (module in modules) {
        try {
            Class.forName(module.registrationClassName)
        } catch (e: ClassNotFoundException) {
            throw IllegalArgumentException(
                "Registration class '${module.registrationClassName}' not found. " +
                "Add the Gradle dependency for '${module.key}'.", e
            )
        }
    }
}

// ============================================================
// SDK Facade
// ============================================================

object KoinSdk {

    private var _koinApp: KoinApplication? = null
    private var _initialized = false
    private var _initializedModules = mutableSetOf<SdkModule>()

    val isInitialized: Boolean get() = _initialized
    val initializedModules: Set<SdkModule> get() = _initializedModules.toSet()

    val koin: Koin
        get() {
            check(_initialized) { "KoinSdk not initialized. Call init() first." }
            return _koinApp!!.koin
        }

    fun init(
        modules: Set<SdkModule>,
        config: SdkConfig = SdkConfig(),
        appModules: List<Module> = emptyList(),
    ) {
        check(!_initialized) { "KoinSdk already initialized. Call shutdown() first." }
        require(modules.isNotEmpty()) { "modules must not be empty." }

        validateNoDuplicateCategories(modules)
        discoverRegistrations(modules)

        val foundation = foundationModule(config)
        val resolved = modules.map { SdkModuleRegistry.resolve(it) }

        val app = koinApplication {
            modules(listOf(foundation) + resolved + appModules)
        }

        _koinApp = app
        _initialized = true
        _initializedModules = modules.toMutableSet()
    }

    /**
     * LAZY INIT — add a module to a running SDK.
     *
     * Koin supports loadModules() on a live koinApplication.
     * This resolves the module's dependencies from the existing graph.
     *
     * If the module has requiredDependencies that aren't initialized yet,
     * they are initialized first (cascade).
     *
     * Returns the set of modules that were actually initialized (including cascaded deps).
     */
    fun getOrInitModule(module: SdkModule): Set<SdkModule> {
        check(_initialized) { "KoinSdk not initialized. Call init() first." }

        if (module in _initializedModules) return emptySet()

        // Cascade: init dependencies first
        val initialized = mutableSetOf<SdkModule>()
        for (dep in module.requiredDependencies) {
            if (dep !in _initializedModules) {
                initialized += getOrInitModule(dep)
            }
        }

        // Discover + load this module
        discoverRegistrations(setOf(module))
        val koinModule = SdkModuleRegistry.resolve(module)
        _koinApp!!.koin.loadModules(listOf(koinModule))
        _initializedModules.add(module)
        initialized.add(module)

        return initialized
    }

    fun shutdown() {
        if (!_initialized) return
        _koinApp?.close()
        _koinApp = null
        _initialized = false
        _initializedModules.clear()
    }

    fun requireModule(module: SdkModule) {
        check(_initialized) { "KoinSdk not initialized. Call init() first." }
        check(module in _initializedModules) {
            "Module '${module.key}' not initialized. Call getOrInitModule() or add it to init()."
        }
    }

    inline fun <reified T : Any> get(): T = koin.get()
}

private fun validateNoDuplicateCategories(modules: Set<SdkModule>) {
    val byCategory = modules.groupBy { it.category }
    val duplicates = byCategory.filter { it.value.size > 1 }
    if (duplicates.isEmpty()) return
    val conflicts = duplicates.entries.joinToString("; ") { (cat, impls) ->
        "$cat: [${impls.joinToString { it.key }}]"
    }
    throw IllegalArgumentException("Duplicate categories — pick one per category. Conflicts: $conflicts")
}

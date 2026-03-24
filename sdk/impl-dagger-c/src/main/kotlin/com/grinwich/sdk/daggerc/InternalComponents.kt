package com.grinwich.sdk.daggerc

import com.grinwich.sdk.api.*
import com.grinwich.sdk.common.*
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton
import java.util.ServiceLoader

// ============================================================
// Feature contract — features implement this + META-INF/services
// ============================================================

interface FeatureInitializer {
    val featureName: String
    val requiredDependencies: Set<String>
    fun init(core: CoreApis, resolved: ServiceResolver)
    fun shutdown()
    fun <T> getService(serviceClass: Class<T>): T?
}

/** Allows features to resolve cross-deps from already-initialized features */
interface ServiceResolver {
    fun <T> resolve(serviceClass: Class<T>): T?
}

// ============================================================
// Internal Components — same as B, per-feature
// ============================================================

@Singleton @Component(modules = [CEncMod::class])
internal interface CEncComp {
    fun encryption(): EncryptionService
    fun hash(): HashService
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: CoreApis): Builder
        fun build(): CEncComp
    }
}
@Module internal class CEncMod {
    @Provides @Singleton fun enc(core: CoreApis): EncryptionService = DefaultEncryptionService(core.logger)
    @Provides @Singleton fun hash(): HashService = DefaultHashService()
}

@CAuthScope @Component(modules = [CAuthMod::class])
internal interface CAuthComp {
    fun auth(): AuthService
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun enc(enc: EncryptionService): Builder
        @dagger.BindsInstance fun logger(l: SdkLogger): Builder
        fun build(): CAuthComp
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) internal annotation class CAuthScope
@Module internal class CAuthMod {
    @Provides @CAuthScope fun auth(enc: EncryptionService, l: SdkLogger): AuthService = DefaultAuthService(enc, l)
}

@CStorScope @Component(modules = [CStorMod::class])
internal interface CStorComp {
    fun storage(): SecureStorageService
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun enc(enc: EncryptionService): Builder
        @dagger.BindsInstance fun hash(h: HashService): Builder
        @dagger.BindsInstance fun logger(l: SdkLogger): Builder
        fun build(): CStorComp
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) internal annotation class CStorScope
@Module internal class CStorMod {
    @Provides @CStorScope fun storage(enc: EncryptionService, h: HashService, l: SdkLogger): SecureStorageService = DefaultSecureStorageService(enc, h, l)
}

@CAnaScope @Component(modules = [CAnaMod::class])
internal interface CAnaComp {
    fun analytics(): AnalyticsService
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: CoreApis): Builder
        fun build(): CAnaComp
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) internal annotation class CAnaScope
@Module internal class CAnaMod {
    @Provides @CAnaScope fun analytics(core: CoreApis): AnalyticsService = DefaultAnalyticsService(core.logger)
}

@CSynScope @Component(modules = [CSynMod::class])
internal interface CSynComp {
    fun sync(): SyncService
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun auth(a: AuthService): Builder
        @dagger.BindsInstance fun storage(s: SecureStorageService): Builder
        @dagger.BindsInstance fun enc(e: EncryptionService): Builder
        @dagger.BindsInstance fun logger(l: SdkLogger): Builder
        fun build(): CSynComp
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) internal annotation class CSynScope
@Module internal class CSynMod {
    @Provides @CSynScope fun sync(a: AuthService, s: SecureStorageService, e: EncryptionService, l: SdkLogger): SyncService = DefaultSyncService(a, s, e, l)
}

// ============================================================
// Built-in feature initializers (registered via META-INF/services)
// ============================================================

class EncryptionInit : FeatureInitializer {
    private var comp: CEncComp? = null
    override val featureName = "encryption"
    override val requiredDependencies = emptySet<String>()
    override fun init(core: CoreApis, resolved: ServiceResolver) {
        comp = DaggerCEncComp.builder().core(core).build()
    }
    override fun shutdown() { comp = null }
    @Suppress("UNCHECKED_CAST")
    override fun <T> getService(serviceClass: Class<T>): T? = when (serviceClass) {
        EncryptionService::class.java -> comp?.encryption() as? T
        HashService::class.java -> comp?.hash() as? T
        else -> null
    }
}

class AuthInit : FeatureInitializer {
    private var comp: CAuthComp? = null
    override val featureName = "auth"
    override val requiredDependencies = setOf("encryption")
    override fun init(core: CoreApis, resolved: ServiceResolver) {
        val enc = resolved.resolve(EncryptionService::class.java)!!
        comp = DaggerCAuthComp.builder().enc(enc).logger(core.logger).build()
    }
    override fun shutdown() { comp = null }
    @Suppress("UNCHECKED_CAST")
    override fun <T> getService(serviceClass: Class<T>): T? = when (serviceClass) {
        AuthService::class.java -> comp?.auth() as? T
        else -> null
    }
}

class StorageInit : FeatureInitializer {
    private var comp: CStorComp? = null
    override val featureName = "storage"
    override val requiredDependencies = setOf("encryption")
    override fun init(core: CoreApis, resolved: ServiceResolver) {
        val enc = resolved.resolve(EncryptionService::class.java)!!
        val hash = resolved.resolve(HashService::class.java)!!
        comp = DaggerCStorComp.builder().enc(enc).hash(hash).logger(core.logger).build()
    }
    override fun shutdown() { comp = null }
    @Suppress("UNCHECKED_CAST")
    override fun <T> getService(serviceClass: Class<T>): T? = when (serviceClass) {
        SecureStorageService::class.java -> comp?.storage() as? T
        else -> null
    }
}

class AnalyticsInit : FeatureInitializer {
    private var comp: CAnaComp? = null
    override val featureName = "analytics"
    override val requiredDependencies = emptySet<String>()
    override fun init(core: CoreApis, resolved: ServiceResolver) {
        comp = DaggerCAnaComp.builder().core(core).build()
    }
    override fun shutdown() { comp = null }
    @Suppress("UNCHECKED_CAST")
    override fun <T> getService(serviceClass: Class<T>): T? = when (serviceClass) {
        AnalyticsService::class.java -> comp?.analytics() as? T
        else -> null
    }
}

class SyncInit : FeatureInitializer {
    private var comp: CSynComp? = null
    override val featureName = "sync"
    override val requiredDependencies = setOf("auth", "storage", "encryption")
    override fun init(core: CoreApis, resolved: ServiceResolver) {
        comp = DaggerCSynComp.builder()
            .auth(resolved.resolve(AuthService::class.java)!!)
            .storage(resolved.resolve(SecureStorageService::class.java)!!)
            .enc(resolved.resolve(EncryptionService::class.java)!!)
            .logger(core.logger).build()
    }
    override fun shutdown() { comp = null }
    @Suppress("UNCHECKED_CAST")
    override fun <T> getService(serviceClass: Class<T>): T? = when (serviceClass) {
        SyncService::class.java -> comp?.sync() as? T
        else -> null
    }
}

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
    fun encryption(): EncryptionApi
    fun hash(): HashApi
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: CoreApis): Builder
        fun build(): CEncComp
    }
}
@Module internal class CEncMod {
    @Provides @Singleton fun enc(core: CoreApis): EncryptionApi = DefaultEncryptionService(core.logger)
    @Provides @Singleton fun hash(): HashApi = DefaultHashService()
}

@CAuthScope @Component(modules = [CAuthMod::class])
internal interface CAuthComp {
    fun auth(): AuthApi
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun enc(enc: EncryptionApi): Builder
        @dagger.BindsInstance fun logger(l: SdkLogger): Builder
        fun build(): CAuthComp
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) internal annotation class CAuthScope
@Module internal class CAuthMod {
    @Provides @CAuthScope fun auth(enc: EncryptionApi, l: SdkLogger): AuthApi = DefaultAuthService(enc, l)
}

@CStorScope @Component(modules = [CStorMod::class])
internal interface CStorComp {
    fun storage(): StorageApi
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun enc(enc: EncryptionApi): Builder
        @dagger.BindsInstance fun hash(h: HashApi): Builder
        @dagger.BindsInstance fun logger(l: SdkLogger): Builder
        fun build(): CStorComp
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) internal annotation class CStorScope
@Module internal class CStorMod {
    @Provides @CStorScope fun storage(enc: EncryptionApi, h: HashApi, l: SdkLogger): StorageApi = DefaultSecureStorageService(enc, h, l)
}

@CAnaScope @Component(modules = [CAnaMod::class])
internal interface CAnaComp {
    fun analytics(): AnalyticsApi
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun core(core: CoreApis): Builder
        fun build(): CAnaComp
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) internal annotation class CAnaScope
@Module internal class CAnaMod {
    @Provides @CAnaScope fun analytics(core: CoreApis): AnalyticsApi = DefaultAnalyticsService(core.logger)
}

@CSynScope @Component(modules = [CSynMod::class])
internal interface CSynComp {
    fun sync(): SyncApi
    @Component.Builder interface Builder {
        @dagger.BindsInstance fun auth(a: AuthApi): Builder
        @dagger.BindsInstance fun storage(s: StorageApi): Builder
        @dagger.BindsInstance fun enc(e: EncryptionApi): Builder
        @dagger.BindsInstance fun logger(l: SdkLogger): Builder
        fun build(): CSynComp
    }
}
@javax.inject.Scope @Retention(AnnotationRetention.RUNTIME) internal annotation class CSynScope
@Module internal class CSynMod {
    @Provides @CSynScope fun sync(a: AuthApi, s: StorageApi, e: EncryptionApi, l: SdkLogger): SyncApi = DefaultSyncService(a, s, e, l)
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
    override fun <T> getService(serviceClass: Class<T>): T? = when (serviceClass) {
        EncryptionApi::class.java -> serviceClass.cast(comp?.encryption())
        HashApi::class.java -> serviceClass.cast(comp?.hash())
        else -> null
    }
}

class AuthInit : FeatureInitializer {
    private var comp: CAuthComp? = null
    override val featureName = "auth"
    override val requiredDependencies = setOf("encryption")
    override fun init(core: CoreApis, resolved: ServiceResolver) {
        val enc = resolved.resolve(EncryptionApi::class.java)!!
        comp = DaggerCAuthComp.builder().enc(enc).logger(core.logger).build()
    }
    override fun shutdown() { comp = null }
    override fun <T> getService(serviceClass: Class<T>): T? = when (serviceClass) {
        AuthApi::class.java -> serviceClass.cast(comp?.auth())
        else -> null
    }
}

class StorageInit : FeatureInitializer {
    private var comp: CStorComp? = null
    override val featureName = "storage"
    override val requiredDependencies = setOf("encryption")
    override fun init(core: CoreApis, resolved: ServiceResolver) {
        val enc = resolved.resolve(EncryptionApi::class.java)!!
        val hash = resolved.resolve(HashApi::class.java)!!
        comp = DaggerCStorComp.builder().enc(enc).hash(hash).logger(core.logger).build()
    }
    override fun shutdown() { comp = null }
    override fun <T> getService(serviceClass: Class<T>): T? = when (serviceClass) {
        StorageApi::class.java -> serviceClass.cast(comp?.storage())
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
    override fun <T> getService(serviceClass: Class<T>): T? = when (serviceClass) {
        AnalyticsApi::class.java -> serviceClass.cast(comp?.analytics())
        else -> null
    }
}

class SyncInit : FeatureInitializer {
    private var comp: CSynComp? = null
    override val featureName = "sync"
    override val requiredDependencies = setOf("auth", "storage", "encryption")
    override fun init(core: CoreApis, resolved: ServiceResolver) {
        comp = DaggerCSynComp.builder()
            .auth(resolved.resolve(AuthApi::class.java)!!)
            .storage(resolved.resolve(StorageApi::class.java)!!)
            .enc(resolved.resolve(EncryptionApi::class.java)!!)
            .logger(core.logger).build()
    }
    override fun shutdown() { comp = null }
    override fun <T> getService(serviceClass: Class<T>): T? = when (serviceClass) {
        SyncApi::class.java -> serviceClass.cast(comp?.sync())
        else -> null
    }
}

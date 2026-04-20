package com.grinwich.sdk.feature.stor

import android.content.Context
import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.api.StorageApi
import com.grinwich.sdk.api.StorageBackend
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.contracts.FeatureProvider
import com.grinwich.sdk.contracts.Flavor
import com.grinwich.sdk.contracts.Resolver
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

/**
 * kotlin-inject Component for Storage. Internal — only [StorKIProvider]
 * instantiates it, and only for the DATA_STORE backend (the other backends
 * are constructed directly to match the structure of other flavors).
 */
@Component
internal abstract class KIStorComponent(
    @get:Provides val context: Context,
    @get:Provides val encryption: EncryptionApi,
    @get:Provides val hash: HashApi,
    @get:Provides val logger: SdkLogger,
) {
    abstract val storage: StorageApi

    @Provides fun storageApi(): StorageApi = DataStoreStorageService(context, encryption, hash, logger)
}

/** Storage provider with flavor [Flavor.KI]. Consumed by pattern J. */
class StorKIProvider : FeatureProvider() {
    override val flavor = Flavor.KI
    override val services = setOf(StorageApi::class.java)

    override fun build(resolver: Resolver): Map<Class<*>, Any> {
        val context = resolver.get(Context::class.java)
        val encryption = resolver.get(EncryptionApi::class.java)
        val hash = resolver.get(HashApi::class.java)
        val logger = resolver.get(SdkLogger::class.java)
        val storage = when (resolver.get(SdkConfig::class.java).storageBackend) {
            StorageBackend.FAKE -> FakeStorageService(encryption, hash, logger)
            StorageBackend.SHARED_PREFS -> SharedPrefsStorageService(context, encryption, hash, logger)
            StorageBackend.DATA_STORE -> {
                val component = KIStorComponent::class.create(
                    context = context,
                    encryption = encryption,
                    hash = hash,
                    logger = logger,
                )
                component.storage
            }
        }
        return mapOf(StorageApi::class.java to storage)
    }
}

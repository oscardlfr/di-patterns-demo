package com.grinwich.sdk.feature.stor

import android.content.Context
import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.api.StorageApi
import com.grinwich.sdk.api.StorageBackend
import com.grinwich.sdk.contracts.*
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides

@Component
abstract class KIStorComponent(
    @get:Provides val context: Context,
    @get:Provides val encryption: EncryptionApi,
    @get:Provides val hash: HashApi,
    @get:Provides val logger: SdkLogger,
) {
    abstract val storage: StorageApi

    @Provides fun storageApi(): StorageApi = DataStoreStorageService(context, encryption, hash, logger)
}

class StorKIProvider : KIFeatureProvider<StorProvisions>(StorProvisions::class.java) {
    override val services: Map<Class<*>, (StorProvisions) -> Any> = mapOf(
        StorageApi::class.java to StorProvisions::storage,
    )
    override fun build(resolver: Resolver): StorProvisions {
        val config = resolver.provision(CoreProvisions::class.java).config()
        val ctx = resolver.provision(ContextProvisions::class.java).appContext()
        val enc = resolver.provision(EncProvisions::class.java)
        val logger = resolver.logger
        val storage = when (config.storageBackend) {
            StorageBackend.FAKE -> FakeStorageService(enc.encryption(), enc.hash(), logger)
            StorageBackend.SHARED_PREFS -> SharedPrefsStorageService(ctx, enc.encryption(), enc.hash(), logger)
            StorageBackend.DATA_STORE -> {
                val component = KIStorComponent::class.create(
                    context = ctx,
                    encryption = enc.encryption(),
                    hash = enc.hash(),
                    logger = logger,
                )
                component.storage
            }
        }
        return object : StorProvisions {
            override fun storage() = storage
        }
    }
}

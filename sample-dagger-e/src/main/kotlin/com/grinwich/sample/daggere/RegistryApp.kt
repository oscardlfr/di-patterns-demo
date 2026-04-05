package com.grinwich.sample.daggere

import android.app.Application
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.registry.RegistrySdk

class RegistryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RegistrySdk.init(SdkConfig(debug = true), setOf(RegistrySdk.Feature.ENCRYPTION))
    }
}

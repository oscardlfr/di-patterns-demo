package com.grinwich.sample.daggerf

import android.app.Application
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.modular.ModularSdk

class ModularApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ModularSdk.init(SdkConfig(debug = true), setOf(ModularSdk.Feature.ENCRYPTION))
    }
}

package com.grinwich.sample.multimodule

import android.app.Application
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.wiring.MultiModuleSdk

class MultiModuleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Only init core. Features built on demand when get<T>() is called.
        MultiModuleSdk.init(SdkConfig(debug = true))
    }
}

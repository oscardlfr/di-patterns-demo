package com.grinwich.sample.multimodule

import android.app.Application
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.wiring.h.MultiModuleSdkH

class MultiModuleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Only registers providers. Features built on demand when get<T>() is called.
        MultiModuleSdkH.init(SdkConfig(debug = true))
    }
}

package com.grinwich.sample.daggerb

import android.app.Application
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.daggerb.DaggerBSdk

class DaggerBApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DaggerBSdk.init(SdkConfig(debug = true), setOf(DaggerBSdk.Feature.ENCRYPTION))
    }
}

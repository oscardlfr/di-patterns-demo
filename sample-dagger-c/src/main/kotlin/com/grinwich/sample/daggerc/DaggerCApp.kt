package com.grinwich.sample.daggerc

import android.app.Application
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.daggerc.DaggerCSdk

class DaggerCApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DaggerCSdk.init(SdkConfig(debug = true), setOf("encryption"))
    }
}

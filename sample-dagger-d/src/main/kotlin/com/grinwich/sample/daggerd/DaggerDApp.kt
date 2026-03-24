package com.grinwich.sample.daggerd

import android.app.Application
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.dagger.DaggerSdk

class DaggerDApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DaggerSdk.init(SdkConfig(debug = true), setOf(DaggerSdk.Feature.ENCRYPTION))
    }
}

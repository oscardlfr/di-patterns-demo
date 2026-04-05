package com.grinwich.sample.daggere2

import android.app.Application
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.auto.AutoSdk

class AutoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // That's it. No Feature enum. No module selection.
        // Components build lazily on first get<T>().
        AutoSdk.init(SdkConfig(debug = true))
    }
}

package com.grinwich.sample.multimodule

import android.app.Application
import com.grinwich.sample.multimodule.di.AppComponent
import com.grinwich.sample.multimodule.di.DaggerAppComponent
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.wiring.h.MultiModuleSdkH

class MultiModuleApp : Application() {

    lateinit var appComponent: AppComponent
        private set

    override fun onCreate() {
        super.onCreate()

        // 1. Initialize the SDK — registers ServiceLoader providers, builds NOTHING yet
        MultiModuleSdkH.init(this, SdkConfig(debug = true))

        // 2. Build the app's Dagger component — bridges SDK services into Dagger
        //    SdkBridgeModule calls MultiModuleSdkH.get<T>() which lazily builds features
        appComponent = DaggerAppComponent.factory().create()
    }
}

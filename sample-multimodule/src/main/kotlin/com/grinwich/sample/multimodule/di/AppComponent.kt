package com.grinwich.sample.multimodule.di

import com.grinwich.sample.multimodule.data.UserRepository
import dagger.Component
import javax.inject.Singleton

/**
 * App-level Dagger component. Bridges SDK services into the app's DI graph.
 *
 * The app never imports SDK implementation modules — only :sdk:api interfaces.
 * SdkBridgeModule resolves services from MultiModuleSdkH.get<T>().
 */
@Singleton
@Component(modules = [SdkBridgeModule::class])
interface AppComponent {

    fun userRepository(): UserRepository

    @Component.Factory
    interface Factory {
        fun create(): AppComponent
    }
}

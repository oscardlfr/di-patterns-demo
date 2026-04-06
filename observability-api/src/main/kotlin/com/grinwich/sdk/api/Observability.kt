package com.grinwich.sdk.api

interface SdkLogger {
    fun d(tag: String, msg: String)
    fun e(tag: String, msg: String, throwable: Throwable? = null)
}

// AndroidSdkLogger (impl) -> feature-core-impl

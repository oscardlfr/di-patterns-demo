package com.grinwich.sdk.api

import android.util.Log

interface SdkLogger {
    fun d(tag: String, msg: String)
    fun e(tag: String, msg: String, throwable: Throwable? = null)
}

/** Default Android implementation of [SdkLogger]. */
class AndroidSdkLogger : SdkLogger {
    override fun d(tag: String, msg: String) = Log.d("SDK-$tag", msg).let { }
    override fun e(tag: String, msg: String, throwable: Throwable?) {
        Log.e("SDK-$tag", msg, throwable)
    }
}

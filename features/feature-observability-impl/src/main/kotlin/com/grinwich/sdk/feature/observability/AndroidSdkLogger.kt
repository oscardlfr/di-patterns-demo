package com.grinwich.sdk.feature.observability

import android.util.Log
import com.grinwich.sdk.api.SdkLogger
import javax.inject.Inject

/** Default Android implementation of [SdkLogger]. */
class AndroidSdkLogger @Inject constructor() : SdkLogger {
    override fun d(tag: String, msg: String) = Log.d("SDK-$tag", msg).let { }
    override fun e(tag: String, msg: String, throwable: Throwable?) {
        Log.e("SDK-$tag", msg, throwable)
    }
}

package com.grinwich.sdk.contracts

import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.api.SdkLogger

/** Core services available to all features. */
interface CoreProvisions {
    fun config(): SdkConfig
    fun logger(): SdkLogger
}

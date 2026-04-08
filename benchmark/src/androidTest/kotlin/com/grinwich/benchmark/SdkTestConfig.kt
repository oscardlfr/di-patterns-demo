package com.grinwich.benchmark

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.grinwich.sdk.api.MultiModuleSdkApi
import com.grinwich.sdk.wiring.MultiModuleSdk
import com.grinwich.sdk.wiring.e2.MultiModuleSdkE2
import com.grinwich.sdk.wiring.g.MultiModuleSdkG
import com.grinwich.sdk.wiring.h.MultiModuleSdkH
import com.grinwich.sdk.wiring.i.MultiModuleSdkI
import com.grinwich.sdk.wiring.j.MultiModuleSdkJ
import com.grinwich.sdk.wiring.k.MultiModuleSdkK

/** Application context for instrumented tests. */
val testContext: Context get() =
    InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

/** All 7 lazy multi-module SDK patterns for parameterized testing. */
val ALL_LAZY_SDKS: List<Pair<String, MultiModuleSdkApi>> = listOf(
    "D" to MultiModuleSdk,
    "E2" to MultiModuleSdkE2,
    "G" to MultiModuleSdkG,
    "H" to MultiModuleSdkH,
    "I" to MultiModuleSdkI,
    "J" to MultiModuleSdkJ,
    "K" to MultiModuleSdkK,
)

/** Expected provision counts per pattern — used by MemoryBehaviorTest. */
data class SdkExpectedCounts(
    val afterInit: Int,
    val afterEnc: Int,
    val afterAna: Int,
    val afterSync: Int,
    val fullGraph: Int,
)

val EXPECTED_COUNTS: Map<String, SdkExpectedCounts> = mapOf(
    "D"  to SdkExpectedCounts(afterInit = 1, afterEnc = 2, afterAna = 2, afterSync = 5, fullGraph = 6),
    "E2" to SdkExpectedCounts(afterInit = 0, afterEnc = 2, afterAna = 2, afterSync = 5, fullGraph = 6),
    "G"  to SdkExpectedCounts(afterInit = 1, afterEnc = 2, afterAna = 2, afterSync = 5, fullGraph = 6),
    "H"  to SdkExpectedCounts(afterInit = 0, afterEnc = 3, afterAna = 3, afterSync = 6, fullGraph = 7),
    "I"  to SdkExpectedCounts(afterInit = 0, afterEnc = 2, afterAna = 2, afterSync = 6, fullGraph = 7),
    "J"  to SdkExpectedCounts(afterInit = 0, afterEnc = 2, afterAna = 2, afterSync = 6, fullGraph = 7),
    "K"  to SdkExpectedCounts(afterInit = 0, afterEnc = 3, afterAna = 3, afterSync = 6, fullGraph = 7),
)

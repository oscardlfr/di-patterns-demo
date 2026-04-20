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
import com.grinwich.sdk.wiring.l.MultiModuleSdkL
import com.grinwich.sdk.wiring.m.MultiModuleSdkM
import com.grinwich.sdk.wiring.n.MultiModuleSdkN
import com.grinwich.sdk.wiring.o.MultiModuleSdkO
import com.grinwich.sdk.wiring.o2.MultiModuleSdkO2
import com.grinwich.sdk.wiring.p.MultiModuleSdkP
import com.grinwich.sdk.wiring.p2.MultiModuleSdkP2
import com.grinwich.sdk.wiring.q.MultiModuleSdkQ
import com.grinwich.sdk.wiring.q2.MultiModuleSdkQ2

/** Application context for instrumented tests. */
val testContext: Context get() =
    InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

/** All 16 lazy multi-module SDK patterns for parameterized testing. */
val ALL_LAZY_SDKS: List<Pair<String, MultiModuleSdkApi>> = listOf(
    "D" to MultiModuleSdk,
    "E2" to MultiModuleSdkE2,
    "G" to MultiModuleSdkG,
    "H" to MultiModuleSdkH,
    "I" to MultiModuleSdkI,
    "J" to MultiModuleSdkJ,
    "K" to MultiModuleSdkK,
    "L" to MultiModuleSdkL,
    "M" to MultiModuleSdkM,
    "N" to MultiModuleSdkN,
    "O" to MultiModuleSdkO,
    "P" to MultiModuleSdkP,
    "Q" to MultiModuleSdkQ,
    "O2" to MultiModuleSdkO2,
    "P2" to MultiModuleSdkP2,
    "Q2" to MultiModuleSdkQ2,
)

/** Expected provision counts per pattern — used by MemoryBehaviorTest. */
data class SdkExpectedCounts(
    val afterInit: Int,
    val afterEnc: Int,
    val afterAna: Int,
    val afterSync: Int,
    val fullGraph: Int,
)

/**
 * Expected feature counts per pattern, after the Observability-persistent refactor.
 *
 * `builtFeatureCount` semantics per pattern family:
 *
 * - **D/G** (hardcoded fields): counts `_enc + _auth + _storage + _analytics + _sync`.
 *   Observability lives as a separate `_logger` field and is NEVER counted.
 *   Max = 5 (all five business features).
 *
 * - **E2** (AutoServiceRegistry): counts non-persistent features built.
 *   `observabilityAutoEntry()` is marked `persistent = true` → excluded.
 *   Max = 6 (Core + Enc + Auth + Stor + Ana + Syn).
 *
 * - **H/I/J/K** (Resolver + FeatureProvider): counts non-persistent built providers.
 *   ObservabilityProvider has `persistent = true` → excluded.
 *   SyntheticFeatureProvider (Context + SdkConfig) is NON-persistent → counted
 *   (only built when StorageProvider requests Context/SdkConfig).
 *   Max = 6 (Enc + Auth + Stor + Syn + Ana + Synthetic).
 *
 * - **L/M/N** (Koin + CreationTracker): tracks `mark()` calls. Observability's
 *   KoinProvider doesn't call `mark()` (infrastructure, not business feature).
 *   Max = 5 (enc/auth/storage/analytics/sync marks).
 *
 * - **O/P/Q** (Metro/Anvil/Hilt eager): full compile-time graph, returns 5
 *   while initialized, 0 after shutdown. All bindings materialized eagerly.
 *
 * - **O2/P2/Q2** (Metro/Anvil/Hilt lazy + LazyCreationTracker): counts lazy
 *   singletons materialized so far. Observability is provided as `@get:Provides`
 *   from a field (not a lazy singleton inside the graph) → not counted.
 *   Max = 5.
 */
val EXPECTED_COUNTS: Map<String, SdkExpectedCounts> = mapOf(
    "D"  to SdkExpectedCounts(afterInit = 0, afterEnc = 1, afterAna = 1, afterSync = 4, fullGraph = 5),
    "E2" to SdkExpectedCounts(afterInit = 0, afterEnc = 1, afterAna = 1, afterSync = 5, fullGraph = 6),
    "G"  to SdkExpectedCounts(afterInit = 0, afterEnc = 1, afterAna = 1, afterSync = 4, fullGraph = 5),
    "H"  to SdkExpectedCounts(afterInit = 0, afterEnc = 1, afterAna = 1, afterSync = 5, fullGraph = 6),
    "I"  to SdkExpectedCounts(afterInit = 0, afterEnc = 1, afterAna = 1, afterSync = 5, fullGraph = 6),
    "J"  to SdkExpectedCounts(afterInit = 0, afterEnc = 1, afterAna = 1, afterSync = 5, fullGraph = 6),
    "K"  to SdkExpectedCounts(afterInit = 0, afterEnc = 1, afterAna = 1, afterSync = 5, fullGraph = 6),
    "L"  to SdkExpectedCounts(afterInit = 0, afterEnc = 1, afterAna = 1, afterSync = 4, fullGraph = 5),
    "M"  to SdkExpectedCounts(afterInit = 0, afterEnc = 1, afterAna = 1, afterSync = 4, fullGraph = 5),
    "N"  to SdkExpectedCounts(afterInit = 0, afterEnc = 1, afterAna = 1, afterSync = 4, fullGraph = 5),
    "O"  to SdkExpectedCounts(afterInit = 5, afterEnc = 5, afterAna = 5, afterSync = 5, fullGraph = 5),
    "P"  to SdkExpectedCounts(afterInit = 5, afterEnc = 5, afterAna = 5, afterSync = 5, fullGraph = 5),
    "Q"  to SdkExpectedCounts(afterInit = 5, afterEnc = 5, afterAna = 5, afterSync = 5, fullGraph = 5),
    "O2" to SdkExpectedCounts(afterInit = 0, afterEnc = 1, afterAna = 1, afterSync = 4, fullGraph = 5),
    "P2" to SdkExpectedCounts(afterInit = 0, afterEnc = 1, afterAna = 1, afterSync = 4, fullGraph = 5),
    "Q2" to SdkExpectedCounts(afterInit = 0, afterEnc = 1, afterAna = 1, afterSync = 4, fullGraph = 5),
)

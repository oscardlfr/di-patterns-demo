package com.grinwich.sample.multimodule

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grinwich.sdk.api.AnalyticsApi
import com.grinwich.sdk.api.AuthApi
import com.grinwich.sdk.api.EncryptionApi
import com.grinwich.sdk.api.HashApi
import com.grinwich.sdk.api.SdkConfig
import com.grinwich.sdk.api.SdkLogger
import com.grinwich.sdk.api.StorageApi
import com.grinwich.sdk.api.SyncApi
import com.grinwich.sdk.contracts.error.DependencyResolutionException
import com.grinwich.sdk.wiring.h.MultiModuleSdkH
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Release-mode integration smoke test for Pattern H.
 *
 * Runs against the **release** APK (R8 applied via `:di-contracts`'s
 * `consumer-rules.pro`). Failure here means the SDK is broken in
 * production for this consumer's classpath:
 *
 *  - Any API listed in [DOCUMENTED_APIS] failing to resolve indicates a
 *    missing `runtimeOnly` declaration, a stripped `META-INF/services`
 *    descriptor, or a missing keep rule.
 *  - The exception thrown will be the typed
 *    [DependencyResolutionException] subtype that pinpoints the cause
 *    (`NoProviderFoundException`, `ProviderBuildException`, etc.).
 *
 * This is the canonical "if this is red, do not ship" test. Run it as a
 * gate in CI for every release.
 */
@RunWith(AndroidJUnit4::class)
class SdkIntegrationTest {

    @Before
    fun setUp() {
        MultiModuleSdkH.init(
            ApplicationProvider.getApplicationContext(),
            SdkConfig(debug = false),
        )
    }

    @After
    fun tearDown() {
        MultiModuleSdkH.shutdown()
    }

    @Test
    fun every_documented_api_resolves_in_release_classpath() {
        val failures = mutableListOf<String>()
        for (api in DOCUMENTED_APIS) {
            try {
                val instance = MultiModuleSdkH.get(api)
                if (instance == null) {
                    failures += "${api.simpleName}: get() returned null"
                }
            } catch (t: Throwable) {
                val cause = t::class.java.simpleName + ": " + (t.message ?: "<no message>")
                failures += "${api.simpleName} → $cause"
            }
        }
        if (failures.isNotEmpty()) {
            fail(
                "SDK release classpath broken — ${failures.size} API(s) cannot resolve.\n" +
                    "Likely causes: missing runtimeOnly(:features:feature-X-impl), stripped\n" +
                    "META-INF/services descriptor, or missing R8/DexGuard keep rule.\n\n" +
                    failures.joinToString("\n") { "  • $it" }
            )
        }
    }

    @Test
    fun resolution_is_singleton_within_lifecycle() {
        val first = MultiModuleSdkH.get(EncryptionApi::class.java)
        val second = MultiModuleSdkH.get(EncryptionApi::class.java)
        assertSame(
            "EncryptionApi must resolve to the same instance across calls",
            first, second,
        )
    }

    @Test
    fun typed_exception_when_unknown_service_requested() {
        try {
            MultiModuleSdkH.get(Unrelated::class.java)
            fail("Expected DependencyResolutionException for unregistered service")
        } catch (e: DependencyResolutionException) {
            assertTrue(
                "Exception message should name the missing service",
                e.message!!.contains("Unrelated"),
            )
        }
    }

    @Test
    fun lifecycle_after_shutdown_reports_not_initialized() {
        MultiModuleSdkH.shutdown()
        try {
            MultiModuleSdkH.get(EncryptionApi::class.java)
            fail("Expected IllegalStateException after shutdown")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("not initialized", ignoreCase = true))
        } finally {
            // Re-init so @After's shutdown does not throw.
            MultiModuleSdkH.init(
                ApplicationProvider.getApplicationContext(),
                SdkConfig(debug = false),
            )
        }
    }

    @Test
    fun logger_is_persistent_across_shutdown_and_reinit() {
        val before = MultiModuleSdkH.get(SdkLogger::class.java)
        assertNotNull(before)

        MultiModuleSdkH.shutdown()
        MultiModuleSdkH.init(
            ApplicationProvider.getApplicationContext(),
            SdkConfig(debug = false),
        )

        val after = MultiModuleSdkH.get(SdkLogger::class.java)
        assertSame(
            "SdkLogger declares persistent=true and must survive a shutdown/init cycle",
            before, after,
        )
    }

    /** Marker class never registered as a service — used in the "no provider" test. */
    private interface Unrelated

    companion object {
        /**
         * Every API the canonical sample app declares it consumes. Update
         * this list when the app starts or stops depending on a feature.
         * Adding a new API here without adding the corresponding
         * `runtimeOnly(:features:feature-X-impl)` will fail
         * [every_documented_api_resolves_in_release_classpath] — which is
         * the desired behaviour.
         */
        private val DOCUMENTED_APIS: List<Class<*>> = listOf(
            SdkLogger::class.java,
            EncryptionApi::class.java,
            HashApi::class.java,
            AuthApi::class.java,
            StorageApi::class.java,
            AnalyticsApi::class.java,
            SyncApi::class.java,
        )
    }
}

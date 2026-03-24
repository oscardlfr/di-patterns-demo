package com.grinwich.sample.hybrid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.grinwich.sdk.api.*
import com.grinwich.sdk.impl.KoinSdk
import com.grinwich.sdk.impl.SdkModule

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as HybridApp
        val bridge = app.bridgeComponent

        setContent {
            MaterialTheme {
                var result by remember { mutableStateOf("Tap 'Run Demo' to start") }
                Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text("Hybrid: Koin SDK + Dagger 2", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("getOrInitModule = REAL (Koin loadModules + cascade)", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        SdkBenchmark.clear()
                        result = buildString {
                            appendLine("=== Hybrid: Koin SDK + Dagger 2 App ===\n")
                            appendLine("Init modules: ${KoinSdk.initializedModules.map { it.key }}\n")

                            // Encryption (Dagger-injected at startup via bridge)
                            val enc = SdkBenchmark.measure("bridge.encryption()") {
                                bridge.encryption()
                            }
                            SdkBenchmark.measure("encrypt+decrypt (Dagger)") {
                                enc.decrypt(enc.encrypt("hello"))
                            }
                            appendLine("✅ Encryption (via Dagger bridge)")

                            // --- Case 1: Analytics (ZERO deps, lazy) ---
                            appendLine("\n--- Case 1: Analytics (zero deps) ---")
                            appendLine("Active before? ${SdkModule.Analytics.Default in KoinSdk.initializedModules}")
                            val analyticsInited = SdkBenchmark.measure("getOrInitModule(analytics)") {
                                KoinSdk.getOrInitModule(SdkModule.Analytics.Default)
                            }
                            appendLine("Cascaded: ${analyticsInited.map { it.key }}")
                            // Lazy feature — access via KoinSdk directly (not through Dagger bridge)
                            val analytics = SdkBenchmark.measure("KoinSdk.get<Analytics>()") {
                                KoinSdk.get<AnalyticsService>()
                            }
                            SdkBenchmark.measure("track event") { analytics.trackEvent("hybrid_screen") }
                            appendLine("✅ Analytics: ${analytics.getTrackedEvents()}")

                            // --- Case 2: Sync (HEAVY deps) ---
                            appendLine("\n--- Case 2: Sync (needs Auth+Storage+Encryption) ---")
                            appendLine("Active before: ${KoinSdk.initializedModules.map { it.key }}")
                            val syncInited = SdkBenchmark.measure("getOrInitModule(sync)") {
                                KoinSdk.getOrInitModule(SdkModule.Sync.Default)
                            }
                            appendLine("Cascaded: ${syncInited.map { it.key }}")
                            appendLine("Active after:  ${KoinSdk.initializedModules.map { it.key }}")

                            // Login first
                            val auth = KoinSdk.get<AuthService>()
                            SdkBenchmark.measure("auth.login") { auth.login("user", "pass") }

                            val sync = SdkBenchmark.measure("KoinSdk.get<Sync>()") {
                                KoinSdk.get<SyncService>()
                            }
                            val syncResult = SdkBenchmark.measure("sync.sync()") { sync.sync() }
                            appendLine("✅ Sync: up=${syncResult.uploaded}, down=${syncResult.downloaded}")

                            appendLine("\n✅ HYBRID: Koin SDK + Dagger 2 bridge:")
                            appendLine("   Startup features → Dagger @Component resolves from Koin")
                            appendLine("   Lazy features → KoinSdk.get() directly (bypass Dagger)")
                            appendLine("   Two containers, one bridge Component")
                            appendLine("   App code never imports Koin")

                            appendLine("\n${SdkBenchmark.report()}")
                        }
                    }) { Text("Run Demo") }
                    Spacer(Modifier.height(16.dp))
                    Text(result, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

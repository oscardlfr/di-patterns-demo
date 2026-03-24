package com.grinwich.sample.daggera

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
import com.grinwich.sdk.api.SdkBenchmark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as DaggerAApp
        val sdk = app.sdkComponent

        setContent {
            MaterialTheme {
                var result by remember { mutableStateOf("Tap 'Run Demo' to start") }
                Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text("Dagger A: Monolithic", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("getOrInitFeature = FAKE (all code already compiled in)", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        SdkBenchmark.clear()
                        result = buildString {
                            appendLine("=== Dagger A: Monolithic ===\n")
                            appendLine("Init modules: ${app.activeModules()}\n")

                            // --- Encryption (active at startup) ---
                            val enc = SdkBenchmark.measure("resolve encryption") { sdk.encryptionService() }
                            SdkBenchmark.measure("encrypt+decrypt") {
                                enc.decrypt(enc.encrypt("hello"))
                            }
                            appendLine("✅ Encryption")

                            // --- Case 1: Analytics (no deps, lazy) ---
                            appendLine("\n--- Case 1: Analytics (zero deps) ---")
                            appendLine("Active before? ${app.isFeatureActive("analytics")}")
                            val analyticsInited = SdkBenchmark.measure("getOrInit(analytics)") {
                                app.getOrInitFeature("analytics")
                            }
                            appendLine("Inited: $analyticsInited")
                            val analytics = SdkBenchmark.measure("resolve analytics") { sdk.analyticsService() }
                            SdkBenchmark.measure("track event") { analytics.trackEvent("screen_view", mapOf("screen" to "settings")) }
                            appendLine("✅ Analytics: ${analytics.getTrackedEvents()}")
                            appendLine("⚠️ Code was ALREADY in binary — only activation flag changed")

                            // --- Case 2: Sync (heavy deps: auth+storage+encryption) ---
                            appendLine("\n--- Case 2: Sync (needs Auth+Storage+Encryption) ---")
                            appendLine("Active before? ${app.isFeatureActive("sync")}")

                            // Auth must be initialized first for sync to work
                            SdkBenchmark.measure("getOrInit(auth)") { app.getOrInitFeature("auth") }
                            SdkBenchmark.measure("getOrInit(storage)") { app.getOrInitFeature("storage") }
                            val syncInited = SdkBenchmark.measure("getOrInit(sync)") { app.getOrInitFeature("sync") }
                            appendLine("Inited: $syncInited")

                            // Login first (Sync requires authenticated user)
                            val auth = sdk.authService()
                            SdkBenchmark.measure("auth.login") { auth.login("user", "pass") }

                            val sync = SdkBenchmark.measure("resolve sync") { sdk.syncService() }
                            val syncResult = SdkBenchmark.measure("sync.sync()") { sync.sync() }
                            appendLine("✅ Sync: uploaded=${syncResult.uploaded}, downloaded=${syncResult.downloaded}")
                            appendLine("⚠️ Dagger resolved Auth+Storage+Encryption automatically")
                            appendLine("⚠️ But ALL were already in the @Component — no real lazy init")

                            appendLine("\nActive now: ${app.activeModules()}")
                            appendLine("\n⚠️ APPROACH A CANNOT DO REAL LAZY INIT")
                            appendLine("   @Component is sealed at compile time.")
                            appendLine("   All feature code is in the binary regardless.")

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

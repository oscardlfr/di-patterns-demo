package com.grinwich.sample.daggerb

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
import com.grinwich.sdk.daggerb.DaggerBSdk
import com.grinwich.sdk.daggerb.DaggerBSdk.Feature

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var result by remember { mutableStateOf("Tap 'Run Demo' to start") }
                Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text("Dagger B: Per-Feature SDK", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("SDK facade hides CoreApis wiring. Consumer sees init/get/getOrInit.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        SdkBenchmark.clear()
                        result = buildString {
                            appendLine("=== Dagger B: Per-Feature SDK ===\n")
                            appendLine("Init: ${DaggerBSdk.initializedModules}\n")

                            appendLine("--- Case 1: Analytics (zero deps) ---")
                            val anaInited = SdkBenchmark.measure("getOrInit(ANALYTICS)") {
                                DaggerBSdk.getOrInitModule(Feature.ANALYTICS)
                            }
                            appendLine("Cascaded: $anaInited")
                            val analytics = DaggerBSdk.get<AnalyticsService>()
                            analytics.trackEvent("screen_view")
                            appendLine("✅ Analytics: ${analytics.getTrackedEvents()}")

                            appendLine("\n--- Case 2: Sync (Auth+Storage+Encryption) ---")
                            appendLine("Before: ${DaggerBSdk.initializedModules}")
                            val syncInited = SdkBenchmark.measure("getOrInit(SYNC)") {
                                DaggerBSdk.getOrInitModule(Feature.SYNC)
                            }
                            appendLine("Cascaded: $syncInited")
                            DaggerBSdk.get<AuthService>().login("user", "pass")
                            val sync = SdkBenchmark.measure("sync()") { DaggerBSdk.get<SyncService>().sync() }
                            appendLine("✅ Sync: up=${sync.uploaded}, down=${sync.downloaded}")
                            appendLine("After: ${DaggerBSdk.initializedModules}")

                            appendLine("\n⚠️ Internally: extended CoreApis per cross-dep")
                            appendLine("${SdkBenchmark.report()}")
                        }
                    }) { Text("Run Demo") }
                    Spacer(Modifier.height(16.dp))
                    Text(result, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

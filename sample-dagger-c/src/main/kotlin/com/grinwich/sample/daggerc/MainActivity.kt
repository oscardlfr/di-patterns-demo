package com.grinwich.sample.daggerc

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
import com.grinwich.sdk.daggerc.DaggerCSdk

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var result by remember { mutableStateOf("Tap 'Run Demo' to start") }
                Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text("Dagger C: ServiceLoader SDK", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("SDK discovers features via META-INF/services. Zero central edits.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        SdkBenchmark.clear()
                        result = buildString {
                            appendLine("=== Dagger C: ServiceLoader SDK ===\n")
                            appendLine("Init: ${DaggerCSdk.initializedModules}\n")

                            appendLine("--- Case 1: Analytics (zero deps) ---")
                            val anaInited = SdkBenchmark.measure("getOrInit(analytics)") {
                                DaggerCSdk.getOrInitModule("analytics")
                            }
                            appendLine("Cascaded: $anaInited")
                            val analytics = DaggerCSdk.get<AnalyticsService>()
                            analytics.trackEvent("page_view")
                            appendLine("✅ Analytics: ${analytics.getTrackedEvents()}")

                            appendLine("\n--- Case 2: Sync (auth+storage+encryption) ---")
                            appendLine("Before: ${DaggerCSdk.initializedModules}")
                            val syncInited = SdkBenchmark.measure("getOrInit(sync)") {
                                DaggerCSdk.getOrInitModule("sync")
                            }
                            appendLine("Cascaded: $syncInited")
                            DaggerCSdk.get<AuthService>().login("user", "pass")
                            val sync = SdkBenchmark.measure("sync()") { DaggerCSdk.get<SyncService>().sync() }
                            appendLine("✅ Sync: up=${sync.uploaded}, down=${sync.downloaded}")
                            appendLine("After: ${DaggerCSdk.initializedModules}")

                            appendLine("\n⚠️ JVM-only (ServiceLoader). Runtime errors if dep missing.")
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

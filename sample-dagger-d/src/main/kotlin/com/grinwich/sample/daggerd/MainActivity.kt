package com.grinwich.sample.daggerd

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
import com.grinwich.sdk.dagger.DaggerSdk
import com.grinwich.sdk.dagger.DaggerSdk.Feature

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var result by remember { mutableStateOf("Tap 'Run Demo' to start") }
                Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text("Dagger D: Component Deps SDK", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Cross-feature automatic via Dagger dependencies=[...]. No CoreApis.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        SdkBenchmark.clear()
                        result = buildString {
                            appendLine("=== Dagger D: Component Dependencies SDK ===\n")
                            appendLine("Init: ${DaggerSdk.initializedModules}\n")

                            appendLine("--- Case 1: Analytics (zero deps) ---")
                            val anaInited = SdkBenchmark.measure("getOrInit(ANALYTICS)") {
                                DaggerSdk.getOrInitModule(Feature.ANALYTICS)
                            }
                            appendLine("Cascaded: $anaInited")
                            val analytics = DaggerSdk.get<AnalyticsService>()
                            analytics.trackEvent("screen_view")
                            appendLine("✅ Analytics: ${analytics.getTrackedEvents()}")

                            appendLine("\n--- Case 2: Sync (Auth+Storage+Encryption) ---")
                            appendLine("Before: ${DaggerSdk.initializedModules}")
                            val syncInited = SdkBenchmark.measure("getOrInit(SYNC)") {
                                DaggerSdk.getOrInitModule(Feature.SYNC)
                            }
                            appendLine("Cascaded: $syncInited")
                            DaggerSdk.get<AuthService>().login("user", "pass")
                            val sync = SdkBenchmark.measure("sync()") { DaggerSdk.get<SyncService>().sync() }
                            appendLine("✅ Sync: up=${sync.uploaded}, down=${sync.downloaded}")
                            appendLine("After: ${DaggerSdk.initializedModules}")

                            appendLine("\n✅ No CoreApis God Object. Dagger resolves cross-deps.")
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

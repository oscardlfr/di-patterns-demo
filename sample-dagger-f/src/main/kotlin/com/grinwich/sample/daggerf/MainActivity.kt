package com.grinwich.sample.daggerf

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
import com.grinwich.sdk.modular.ModularSdk
import com.grinwich.sdk.modular.ModularSdk.Feature

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var result by remember { mutableStateOf("Tap 'Run Demo' to start") }
                Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text("Dagger F: Multi-Module", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("D for multi-module Gradle. CoreComponent in :sdk:di-core, no registry overhead.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        SdkBenchmark.clear()
                        result = buildString {
                            appendLine("=== Dagger F: Multi-Module SDK ===\n")
                            appendLine("Init: ${ModularSdk.initializedModules}\n")

                            appendLine("--- Case 1: Analytics (zero deps) ---")
                            val anaInited = SdkBenchmark.measure("getOrInit(ANALYTICS)") {
                                ModularSdk.getOrInitModule(Feature.ANALYTICS)
                            }
                            appendLine("Cascaded: $anaInited")
                            val analytics = ModularSdk.get<AnalyticsService>()
                            analytics.trackEvent("screen_view")
                            appendLine("Analytics: ${analytics.getTrackedEvents()}")

                            appendLine("\n--- Case 2: Sync (Auth+Storage+Encryption) ---")
                            appendLine("Before: ${ModularSdk.initializedModules}")
                            val syncInited = SdkBenchmark.measure("getOrInit(SYNC)") {
                                ModularSdk.getOrInitModule(Feature.SYNC)
                            }
                            appendLine("Cascaded: $syncInited")
                            ModularSdk.get<AuthService>().login("user", "pass")
                            val sync = SdkBenchmark.measure("sync()") { ModularSdk.get<SyncService>().sync() }
                            appendLine("Sync: up=${sync.uploaded}, down=${sync.downloaded}")
                            appendLine("After: ${ModularSdk.initializedModules}")

                            appendLine("\nD for multi-module. No registry. No reflection.")
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

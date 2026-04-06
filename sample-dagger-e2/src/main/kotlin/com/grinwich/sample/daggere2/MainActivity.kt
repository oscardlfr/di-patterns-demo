package com.grinwich.sample.daggere2

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
import com.grinwich.sdk.auto.AutoSdk

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var result by remember { mutableStateOf("Tap 'Run Demo' to start") }
                Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                    Text("Dagger E2: Auto-Init Registry", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("No Feature enum. Auto-init on get<T>(). Facade never changes.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        SdkBenchmark.clear()
                        result = buildString {
                            appendLine("=== Dagger E2: Auto-Init Registry ===\n")

                            appendLine("--- Case 1: Analytics (auto-inits Core) ---")
                            val analytics = SdkBenchmark.measure("get<AnalyticsApi>()") {
                                AutoSdk.get<AnalyticsApi>()
                            }
                            analytics.trackEvent("screen_view")
                            appendLine("Analytics: ${analytics.getTrackedEvents()}")

                            appendLine("\n--- Case 2: Sync (auto-cascades all deps) ---")
                            val sync = SdkBenchmark.measure("get<SyncApi>()") {
                                AutoSdk.get<SyncApi>()
                            }
                            // Auth auto-built by Sync's dependency chain
                            AutoSdk.get<AuthApi>().login("user", "pass")
                            val syncResult = SdkBenchmark.measure("sync()") { sync.sync() }
                            appendLine("Sync: up=${syncResult.uploaded}, down=${syncResult.downloaded}")

                            appendLine("\n--- Case 3: Already-built services (cache hit) ---")
                            val enc = SdkBenchmark.measure("get<EncryptionApi>() [cached]") {
                                AutoSdk.get<EncryptionApi>()
                            }
                            appendLine("Encrypt: ${enc.encrypt("hello")}")

                            appendLine("\nNo Feature enum. No module selection.")
                            appendLine("Just get<T>() — everything auto-initializes.")
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

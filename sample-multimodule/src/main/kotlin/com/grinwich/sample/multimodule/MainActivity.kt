package com.grinwich.sample.multimodule

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.grinwich.sdk.api.*
import com.grinwich.sdk.wiring.MultiModuleSdk

/**
 * Demonstrates realistic multi-module SDK consumption.
 *
 * Notice: this app ONLY imports from :sdk:api and :sdk:sdk-wiring.
 * It has ZERO knowledge of Dagger, Components, provision interfaces,
 * or any feature impl module. That's the whole point.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Auto-builds Encryption + Auth on demand (cascading)
        val auth: AuthApi = MultiModuleSdk.get()
        val token = auth.login("demo-user", "s3cr3t")
        Log.d("MultiModule", "Auth token: ${token.accessToken}")

        // Auto-builds Storage on demand (Encryption already cached)
        val storage: StorageApi = MultiModuleSdk.get()
        storage.put("sync-queue", "pending-data")

        // Auto-builds Sync on demand (Auth + Storage + Enc already cached)
        val sync: SyncApi = MultiModuleSdk.get()
        val result = sync.sync()
        Log.d("MultiModule", "Sync: ${result.uploaded} up, ${result.downloaded} down")

        // Analytics — standalone, only needs Core (already cached)
        val analytics: AnalyticsApi = MultiModuleSdk.get()
        analytics.trackEvent("app_launched")

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Text("Multi-Module SDK Demo", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(16.dp))
                        Text("Auth: ${token.accessToken.take(20)}...")
                        Text("Storage: ${storage.get("sync-queue")}")
                        Text("Sync: ${result.uploaded} up, ${result.downloaded} down")
                        Text("Analytics events: ${analytics.getTrackedEvents()}")
                        Spacer(Modifier.height(24.dp))
                        Text(
                            "This app depends ONLY on :sdk:sdk-wiring.\n" +
                            "Zero imports from feature impl modules.\n" +
                            "Zero knowledge of Dagger or Components.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

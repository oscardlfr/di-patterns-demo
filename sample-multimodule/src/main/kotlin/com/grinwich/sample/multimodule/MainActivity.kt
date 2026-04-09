package com.grinwich.sample.multimodule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.grinwich.sample.multimodule.data.UserRepository
import com.grinwich.sdk.api.*
import com.grinwich.sdk.wiring.h.MultiModuleSdkH
import kotlinx.coroutines.launch

/**
 * Demonstrates two SDK consumption patterns in one Activity:
 *
 * 1. **Via Dagger** (recommended for apps with existing Dagger setup):
 *    AppComponent -> SdkBridgeModule -> UserRepository -> constructor injection
 *
 * 2. **Direct** (simplest possible -- no app-side DI):
 *    MultiModuleSdkH.get<EncryptionApi>()
 *
 * Both work. The Dagger approach is cleaner for large apps.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as MultiModuleApp
        val repo = app.appComponent.userRepository()

        // Direct SDK access (no Dagger) -- for simple use cases
        val encryption: EncryptionApi = MultiModuleSdkH.get()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SdkDemoScreen(repo, encryption)
                }
            }
        }
    }
}

@Composable
fun SdkDemoScreen(repo: UserRepository, encryption: EncryptionApi) {
    var loginStatus by remember { mutableStateOf("Not logged in") }
    var encryptedText by remember { mutableStateOf("") }
    var lastUser by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    repo.trackScreen("SdkDemoScreen")

    LaunchedEffect(Unit) {
        lastUser = repo.lastUser()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Pattern H -- SDK + Dagger2 Integration",
            style = MaterialTheme.typography.headlineSmall,
        )

        HorizontalDivider()

        // -- Dagger-injected repository --
        Text("Via Dagger (UserRepository)", style = MaterialTheme.typography.titleMedium)

        Button(onClick = {
            scope.launch {
                val token = repo.login("demo_user", "password123")
                loginStatus = "Logged in: ${token.accessToken.take(25)}..."
                lastUser = repo.lastUser()
            }
        }) { Text("Login via Repository") }

        Text(loginStatus)
        Text("Last user: ${lastUser ?: "none"}")
        Text("Is authenticated: ${repo.isLoggedIn()}")

        HorizontalDivider()

        // -- Direct SDK access --
        Text("Direct SDK (no Dagger)", style = MaterialTheme.typography.titleMedium)

        Button(onClick = {
            encryptedText = encryption.encrypt("Hello SDK!")
        }) { Text("Encrypt 'Hello SDK!'") }

        if (encryptedText.isNotEmpty()) {
            Text("Encrypted: $encryptedText")
            Text("Decrypted: ${encryption.decrypt(encryptedText)}")
        }

        HorizontalDivider()

        // -- Architecture info --
        Text(
            "This app depends ONLY on :sdk:wiring-h.\n" +
                "Dagger bridge resolves SDK services via MultiModuleSdkH.get<T>().\n" +
                "UserRepository uses @Inject constructor -- zero SDK knowledge.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

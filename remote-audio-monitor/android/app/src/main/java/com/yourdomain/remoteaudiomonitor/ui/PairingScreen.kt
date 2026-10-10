package com.yourdomain.remoteaudiomonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yourdomain.remoteaudiomonitor.pairing.PairingManager
import kotlinx.coroutines.launch

@Composable
fun PairingScreen(onPaired: () -> Unit) {
    val context = LocalContext.current
    val pairingManager = remember { PairingManager(context) }
    val scope = rememberCoroutineScope()

    var code by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(pairingManager.suggestedDeviceName()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Pair this device", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Enter the pairing code shown on your dashboard. This only needs to be done once.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
        )

        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            label = { Text("Device name") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase() },
            label = { Text("Pairing code") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )

        error?.let {
            Text(it, color = Color.Red, modifier = Modifier.padding(top = 8.dp))
        }

        Button(
            onClick = {
                error = null
                loading = true
                scope.launch {
                    when (val result = pairingManager.claimPairingCode(code, deviceName)) {
                        is PairingManager.PairingResult.Success -> {
                            loading = false
                            onPaired()
                        }
                        is PairingManager.PairingResult.Failure -> {
                            loading = false
                            error = result.message
                        }
                    }
                }
            },
            enabled = !loading && code.isNotBlank() && deviceName.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            }
            Text("Pair device")
        }
    }
}

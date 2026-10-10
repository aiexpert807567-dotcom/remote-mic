package com.yourdomain.remoteaudiomonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.yourdomain.remoteaudiomonitor.pairing.PairingManager
import com.yourdomain.remoteaudiomonitor.ui.PairingScreen
import com.yourdomain.remoteaudiomonitor.ui.SignInScreen
import kotlinx.coroutines.launch

enum class MicStatus { IDLE, ACTIVE, PERMISSION_DENIED }

enum class AppStage { SIGN_IN, PAIRING, MAIN }

class MainActivity : ComponentActivity() {

    private lateinit var pairingManager: PairingManager

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val micGranted = results[Manifest.permission.RECORD_AUDIO] ?: false
            if (micGranted) {
                startMicService()
            } else {
                micStatusState.value = MicStatus.PERMISSION_DENIED
            }
        }

    private val micStatusState = mutableStateOf(MicStatus.IDLE)
    private val appStageState = mutableStateOf(AppStage.SIGN_IN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pairingManager = PairingManager(this)

        // Decide the starting screen from real state, not guesswork:
        // already signed in + already paired -> go straight to main
        // controls, skipping both one-time screens permanently.
        appStageState.value = when {
            FirebaseAuth.getInstance().currentUser == null -> AppStage.SIGN_IN
            !pairingManager.isPaired() -> AppStage.PAIRING
            else -> AppStage.MAIN
        }

        // Fire a heartbeat immediately if we're already paired, so the
        // dashboard reflects "online" as soon as the app is opened —
        // not just when a mic session starts.
        if (appStageState.value == AppStage.MAIN) {
            sendHeartbeat("online")
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (appStageState.value) {
                        AppStage.SIGN_IN -> SignInScreen(
                            onSignedIn = {
                                val nowPaired = pairingManager.isPaired()
                                appStageState.value = if (nowPaired) AppStage.MAIN else AppStage.PAIRING
                                if (nowPaired) sendHeartbeat("online")
                            }
                        )
                        AppStage.PAIRING -> PairingScreen(
                            onPaired = {
                                appStageState.value = AppStage.MAIN
                                sendHeartbeat("online")
                            }
                        )
                        AppStage.MAIN -> MainScreen(
                            status = micStatusState.value,
                            onStartClick = { checkPermissionsAndStart() },
                            onStopClick = { stopMicService() }
                        )
                    }
                }
            }
        }
    }

    private fun sendHeartbeat(status: String) {
        lifecycleScope.launch {
            pairingManager.sendHeartbeat(status)
        }
    }

    private fun checkPermissionsAndStart() {
        val requiredPermissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            startMicService()
        } else {
            requestPermissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startMicService() {
        val intent = Intent(this, Class.forName("com.yourdomain.remoteaudiomonitor.service.MicrophoneForegroundService"))
        ContextCompat.startForegroundService(this, intent)
        micStatusState.value = MicStatus.ACTIVE
        sendHeartbeat("listening")
    }

    private fun stopMicService() {
        val intent = Intent(this, Class.forName("com.yourdomain.remoteaudiomonitor.service.MicrophoneForegroundService"))
        stopService(intent)
        micStatusState.value = MicStatus.IDLE
        sendHeartbeat("online")
    }
}

@Composable
fun MainScreen(status: MicStatus, onStartClick: () -> Unit, onStopClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val statusText = when (status) {
            MicStatus.IDLE -> stringResource(R.string.status_idle)
            MicStatus.ACTIVE -> stringResource(R.string.status_active)
            MicStatus.PERMISSION_DENIED -> stringResource(R.string.status_permission_denied)
        }
        Text(text = statusText)
        Button(onClick = onStartClick, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.start_session))
        }
        Button(onClick = onStopClick, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.stop_session))
        }
    }
}

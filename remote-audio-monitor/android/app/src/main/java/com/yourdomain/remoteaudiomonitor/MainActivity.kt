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
import com.yourdomain.remoteaudiomonitor.session.SessionManager
import com.yourdomain.remoteaudiomonitor.ui.PairingScreen
import com.yourdomain.remoteaudiomonitor.ui.SignInScreen
import kotlinx.coroutines.launch

enum class MicStatus { IDLE, ACTIVE, PERMISSION_DENIED }

enum class AppStage { SIGN_IN, PAIRING, MAIN }

class MainActivity : ComponentActivity() {

    private lateinit var pairingManager: PairingManager
    private var sessionManager: SessionManager? = null

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

        appStageState.value = when {
            FirebaseAuth.getInstance().currentUser == null -> AppStage.SIGN_IN
            !pairingManager.isPaired() -> AppStage.PAIRING
            else -> AppStage.MAIN
        }

        if (appStageState.value == AppStage.MAIN) {
            sendHeartbeat("online")
            startSessionWatching()
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (appStageState.value) {
                        AppStage.SIGN_IN -> SignInScreen(
                            onSignedIn = {
                                val nowPaired = pairingManager.isPaired()
                                appStageState.value = if (nowPaired) AppStage.MAIN else AppStage.PAIRING
                                if (nowPaired) {
                                    sendHeartbeat("online")
                                    startSessionWatching()
                                }
                            }
                        )
                        AppStage.PAIRING -> PairingScreen(
                            onPaired = {
                                appStageState.value = AppStage.MAIN
                                sendHeartbeat("online")
                                startSessionWatching()
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

    /**
     * Begins watching Firestore for session requests aimed at this
     * device. For now, a requested session is auto-acknowledged
     * (moved to "connecting") to confirm the round trip works end to
     * end — actual WebRTC connection setup happens in a later step.
     */
    private fun startSessionWatching() {
        val deviceDocId = pairingManager.getPairedDeviceDocId() ?: return
        val manager = SessionManager(deviceDocId)
        sessionManager = manager

        manager.startWatching(object : SessionManager.SessionListener {
            override fun onSessionRequested(sessionId: String) {
                lifecycleScope.launch {
                    manager.acknowledgeSession(sessionId)
                    // TODO(Step 2 — WebRTC): begin real offer/answer exchange
                    // here instead of immediately marking active. For now
                    // this confirms the signaling round trip end to end.
                    manager.markSessionActive(sessionId)
                }
            }

            override fun onSessionEnded(sessionId: String) {
                // No audio transport yet to tear down — placeholder for
                // when WebRTC connection cleanup belongs here.
            }
        })
    }

    override fun onDestroy() {
        sessionManager?.stopWatching()
        super.onDestroy()
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

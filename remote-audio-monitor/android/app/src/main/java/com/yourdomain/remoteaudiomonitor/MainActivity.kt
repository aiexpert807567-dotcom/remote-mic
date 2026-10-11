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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.yourdomain.remoteaudiomonitor.pairing.PairingManager
import com.yourdomain.remoteaudiomonitor.service.MicrophoneForegroundService
import com.yourdomain.remoteaudiomonitor.session.SessionManager
import com.yourdomain.remoteaudiomonitor.ui.PairingScreen
import com.yourdomain.remoteaudiomonitor.ui.SignInScreen
import kotlinx.coroutines.launch

enum class SessionStatus { IDLE, CONNECTING, ACTIVE, PERMISSION_DENIED, ERROR }

enum class AppStage { SIGN_IN, PAIRING, MAIN }

class MainActivity : ComponentActivity() {

    private lateinit var pairingManager: PairingManager
    private var sessionManager: SessionManager? = null
    private var pendingSessionId: String? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val micGranted = results[Manifest.permission.RECORD_AUDIO] ?: false
            val sessionId = pendingSessionId
            pendingSessionId = null

            if (sessionId == null) return@registerForActivityResult

            if (micGranted) {
                beginSession(sessionId)
            } else {
                sessionStatusState.value = SessionStatus.PERMISSION_DENIED
                lifecycleScope.launch {
                    sessionManager?.markSessionError(sessionId, "Microphone permission denied")
                }
            }
        }

    private val sessionStatusState = mutableStateOf(SessionStatus.IDLE)
    private val appStageState = mutableStateOf(AppStage.SIGN_IN)
    private var activeSessionId: String? = null

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
                            status = sessionStatusState.value,
                            onStopClick = { stopActiveSession() }
                        )
                    }
                }
            }
        }
    }

    private fun startSessionWatching() {
        val deviceDocId = pairingManager.getPairedDeviceDocId() ?: return
        val manager = SessionManager(deviceDocId)
        sessionManager = manager

        manager.startWatching(object : SessionManager.SessionListener {
            override fun onSessionRequested(sessionId: String) {
                val hasPermission = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                lifecycleScope.launch { manager.acknowledgeSession(sessionId) }
                sessionStatusState.value = SessionStatus.CONNECTING

                if (hasPermission) {
                    beginSession(sessionId)
                } else {
                    pendingSessionId = sessionId
                    val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    requestPermissionLauncher.launch(permissions.toTypedArray())
                }
            }

            override fun onSessionEnded(sessionId: String) {
                activeSessionId = null
                sessionStatusState.value = SessionStatus.IDLE
                sendHeartbeat("online")
            }
        })
    }

    private fun beginSession(sessionId: String) {
        activeSessionId = sessionId
        val deviceDocId = pairingManager.getPairedDeviceDocId() ?: return
        val intent = Intent(this, MicrophoneForegroundService::class.java).apply {
            putExtra(MicrophoneForegroundService.EXTRA_SESSION_ID, sessionId)
            putExtra(MicrophoneForegroundService.EXTRA_DEVICE_DOC_ID, deviceDocId)
        }
        ContextCompat.startForegroundService(this, intent)
        sendHeartbeat("listening")
        sessionStatusState.value = SessionStatus.ACTIVE
    }

    private fun stopActiveSession() {
        val intent = Intent(this, MicrophoneForegroundService::class.java).apply {
            action = MicrophoneForegroundService.ACTION_STOP
        }
        startService(intent)
        activeSessionId = null
        sessionStatusState.value = SessionStatus.IDLE
        sendHeartbeat("online")
    }

    private fun sendHeartbeat(status: String) {
        lifecycleScope.launch { pairingManager.sendHeartbeat(status) }
    }

    override fun onDestroy() {
        sessionManager?.stopWatching()
        super.onDestroy()
    }
}

@Composable
fun MainScreen(status: SessionStatus, onStopClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val statusText = when (status) {
            SessionStatus.IDLE -> "Ready — waiting for a session request from your dashboard"
            SessionStatus.CONNECTING -> "Connecting…"
            SessionStatus.ACTIVE -> "Listening session active"
            SessionStatus.PERMISSION_DENIED -> "Microphone permission denied"
            SessionStatus.ERROR -> "Something went wrong"
        }
        Text(text = statusText)
        if (status == SessionStatus.ACTIVE || status == SessionStatus.CONNECTING) {
            Button(onClick = onStopClick, modifier = Modifier.padding(top = 16.dp)) {
                Text("Stop session")
            }
        }
    }
}

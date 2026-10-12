package com.yourdomain.remoteaudiomonitor.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yourdomain.remoteaudiomonitor.MainActivity
import com.yourdomain.remoteaudiomonitor.R
import com.yourdomain.remoteaudiomonitor.RemoteAudioMonitorApp
import com.yourdomain.remoteaudiomonitor.session.SessionManager
import com.yourdomain.remoteaudiomonitor.webrtc.WebRtcClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the microphone for the duration of one
 * WebRTC session. Started with EXTRA_SESSION_ID by MainActivity when a
 * session request arrives while the app is open and the user is already
 * signed in and paired — this is the explicit activation this app
 * requires; it does not and cannot start silently from a push while
 * force-stopped, nor does it auto-resume after reboot, per this
 * project's own requirements.
 */
class MicrophoneForegroundService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.yourdomain.remoteaudiomonitor.action.STOP_MIC_SERVICE"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_DEVICE_DOC_ID = "device_doc_id"
    }

    private var webRtcClient: WebRtcClient? = null
    private var currentSessionId: String? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            endCurrentSessionAndStop()
            return START_NOT_STICKY
        }

        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        val deviceDocId = intent?.getStringExtra(EXTRA_DEVICE_DOC_ID)

        if (sessionId == null || deviceDocId == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        currentSessionId = sessionId

        val hasPermission = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            serviceScope.launch { SessionManager(deviceDocId, com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "").markSessionError(sessionId, "Microphone permission not granted") }
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            serviceScope.launch { SessionManager(deviceDocId, com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "").markSessionError(sessionId, "Foreground start refused by OS") }
            stopSelf()
            return START_NOT_STICKY
        }

        startWebRtc(sessionId, deviceDocId)
        return START_NOT_STICKY
    }

    private fun startWebRtc(sessionId: String, deviceDocId: String) {
        val client = WebRtcClient(this)
        webRtcClient = client
        val sessionManager = SessionManager(deviceDocId, com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "")

        serviceScope.launch {
            client.start(sessionId, object : WebRtcClient.ConnectionListener {
                override fun onConnected() {
                    serviceScope.launch { sessionManager.markSessionActive(sessionId) }
                }
                override fun onDisconnected() {
                    // Transient — WebRTC may recover. No state change here;
                    // onFailed handles the terminal case.
                }
                override fun onFailed() {
                    serviceScope.launch {
                        sessionManager.markSessionError(sessionId, "Connection failed")
                    }
                    stopSelfCleanly()
                }
                override fun onIceStateChange(state: String) {
                    serviceScope.launch { sessionManager.updateIceState(sessionId, state) }
                }
            })
        }
    }

    private fun endCurrentSessionAndStop() {
        val sessionId = currentSessionId
        if (sessionId != null) {
            // Device-doc id isn't tracked separately once a session is
            // running, so end the session directly via a short-lived
            // SessionManager built from the known pairing — the doc id
            // for ending a session is not required by the ownership
            // check, only session document id is needed.
            serviceScope.launch {
                com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    .collection("sessions").document(sessionId)
                    .update(
                        mapOf(
                            "state" to "ended",
                            "endedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                        )
                    )
            }
        }
        stopSelfCleanly()
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, MicrophoneForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, RemoteAudioMonitorApp.MIC_SERVICE_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_stop)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.notification_stop_action), stopPendingIntent)
            .build()
    }

    private fun stopSelfCleanly() {
        webRtcClient?.stop()
        webRtcClient = null
        currentSessionId = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        webRtcClient?.stop()
        serviceScope.cancel()
        super.onDestroy()
    }
}

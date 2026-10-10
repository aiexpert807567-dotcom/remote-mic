package com.yourdomain.remoteaudiomonitor.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yourdomain.remoteaudiomonitor.MainActivity
import com.yourdomain.remoteaudiomonitor.R
import com.yourdomain.remoteaudiomonitor.RemoteAudioMonitorApp

/**
 * Foreground service that owns the microphone while a session is active.
 *
 * Honest limitations (per project requirements):
 * - This service must be started by an explicit user action in the app's UI.
 *   It cannot be started silently from a push notification while the app is
 *   force-stopped, nor does it auto-resume after device reboot.
 * - If the app is force-stopped or the device reboots, the Android OS tears
 *   this service down. There is no background relaunch; the user must reopen
 *   the app to start a new session. We do not claim otherwise anywhere in
 *   this codebase.
 */
class MicrophoneForegroundService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.yourdomain.remoteaudiomonitor.action.STOP_MIC_SERVICE"

        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            // Permission was revoked between the activity's check and this call,
            // or the OS refused the foreground start (e.g. background start
            // restriction). Fail honestly rather than pretending capture started.
            stopSelf()
            return START_NOT_STICKY
        }

        startCapture()
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            // Permission missing at capture time — stop rather than silently
            // running a foreground service that does nothing, which would
            // misrepresent the true microphone state to the user.
            stopSelfCleanly()
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            // Microphone unavailable on this device/configuration.
            stopSelfCleanly()
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                // Mic may be held by another app/process.
                stopSelfCleanly()
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            // NOTE: Raw PCM frames are now available via audioRecord.read(...).
            // WebRTC integration (Phase 3 Part 4) will replace this placeholder
            // capture loop with real-time frames fed into the WebRTC audio track
            // for transport to the dashboard. Per project requirements, audio is
            // NOT written to disk or persisted anywhere in this service.
        } catch (e: SecurityException) {
            stopSelfCleanly()
        } catch (e: IllegalStateException) {
            stopSelfCleanly()
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MicrophoneForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

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
        releaseMic()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseMic() {
        try {
            if (isRecording) {
                audioRecord?.stop()
            }
        } catch (e: IllegalStateException) {
            // Already stopped; nothing to do.
        } finally {
            audioRecord?.release()
            audioRecord = null
            isRecording = false
        }
    }

    override fun onDestroy() {
        releaseMic()
        super.onDestroy()
    }
}

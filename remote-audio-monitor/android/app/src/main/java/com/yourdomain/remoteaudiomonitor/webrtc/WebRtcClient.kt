package com.yourdomain.remoteaudiomonitor.webrtc

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date

/**
 * Phone-side WebRTC client. Captures microphone audio (low-bitrate Opus,
 * sufficient for clear voice, not music), creates an offer, exchanges
 * signaling via the sessions/{id}/messages Firestore subcollection, and
 * reports connection state back to the caller.
 *
 * TURN/STUN credentials are fetched from the app's own Vercel endpoint —
 * never calls Metered directly, keeping the secret key server-side only.
 */
class WebRtcClient(private val context: Context) {

    companion object {
        // Same endpoint the web dashboard uses — single source of truth
        // for ICE server credentials, secret key never touches this app.
        private const val ICE_SERVERS_URL = "https://remote-mic.vercel.app/api/turn-credentials"

        // Low bitrate — clear voice, not music quality. Keeps TURN usage
        // low and matches the explicit "just need clear voice" requirement.
        private const val OPUS_MAX_AVERAGE_BITRATE = 24000
    }

    interface ConnectionListener {
        fun onConnected()
        fun onDisconnected()
        fun onFailed()
        fun onIceStateChange(state: String) {}
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var eglBase: EglBase? = null
    private var signalingListener: ListenerRegistration? = null
    private val firestore = FirebaseFirestore.getInstance()

    private fun initFactory() {
        if (peerConnectionFactory != null) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )

        eglBase = EglBase.create()

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private suspend fun fetchIceServers(): List<PeerConnection.IceServer> = withContext(Dispatchers.IO) {
        try {
            val url = URL(ICE_SERVERS_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val serversArray: JSONArray = json.getJSONArray("iceServers")

            val servers = mutableListOf<PeerConnection.IceServer>()
            for (i in 0 until serversArray.length()) {
                val entry = serversArray.getJSONObject(i)
                val urls = entry.getString("urls")
                val builder = PeerConnection.IceServer.builder(urls)
                if (entry.has("username")) builder.setUsername(entry.getString("username"))
                if (entry.has("credential")) builder.setPassword(entry.getString("credential"))
                servers.add(builder.createIceServer())
            }
            servers
        } catch (e: Exception) {
            // Fall back to STUN-only — some direct connections will still work.
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        }
    }

    suspend fun start(sessionId: String, listener: ConnectionListener) {
        initFactory()
        val iceServers = fetchIceServers()

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val pc = peerConnectionFactory!!.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    sendSignal(
                        sessionId,
                        "ice-candidate",
                        JSONObject().apply {
                            put("sdpMid", candidate.sdpMid)
                            put("sdpMLineIndex", candidate.sdpMLineIndex)
                            put("candidate", candidate.sdp)
                        }
                    )
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> listener.onConnected()
                        PeerConnection.PeerConnectionState.DISCONNECTED -> listener.onDisconnected()
                        PeerConnection.PeerConnectionState.FAILED -> listener.onFailed()
                        else -> {}
                    }
                }

                override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) { p0?.let { listener.onIceStateChange(it.name) } }
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: org.webrtc.DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onAddTrack(p0: org.webrtc.RtpReceiver?, p1: Array<out MediaStream>?) {}
            }
        )
        peerConnection = pc

        // Local audio track — WebRTC's own audio device module handles
        // mic capture; no manual AudioRecord management needed here.
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        audioSource = peerConnectionFactory!!.createAudioSource(audioConstraints)
        audioTrack = peerConnectionFactory!!.createAudioTrack("audio_track", audioSource)
        pc?.addTrack(audioTrack, listOf("audio_stream"))

        // Offer + low-bitrate SDP munging
        pc?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                val limited = SessionDescription(desc.type, capOpusBitrate(desc.description))
                pc.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        sendSignal(
                            sessionId,
                            "offer",
                            JSONObject().apply {
                                put("type", limited.type.canonicalForm())
                                put("sdp", limited.description)
                            }
                        )
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, limited)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())

        listenForSignals(sessionId)
    }

    private fun capOpusBitrate(sdp: String): String {
        // Insert maxaveragebitrate into the opus fmtp line so the encoder
        // targets a low bitrate — clear voice, minimal TURN usage.
        return sdp.lineSequence().joinToString("\r\n") { line ->
            if (line.startsWith("a=fmtp") && line.contains("111")) {
                "$line;maxaveragebitrate=$OPUS_MAX_AVERAGE_BITRATE"
            } else line
        }
    }

    private fun sendSignal(sessionId: String, type: String, payload: JSONObject) {
        val data = hashMapOf(
            "type" to type,
            "payload" to jsonToMap(payload),
            "senderRole" to "phone",
            "createdAt" to FieldValue.serverTimestamp(),
            "expiresAt" to com.google.firebase.Timestamp(Date(System.currentTimeMillis() + 30 * 60 * 1000))
        )
        firestore.collection("sessions").document(sessionId).collection("messages").add(data)
    }

    private fun jsonToMap(json: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        json.keys().forEach { key -> map[key] = json.get(key) }
        return map
    }

    private fun listenForSignals(sessionId: String) {
        signalingListener = firestore.collection("sessions").document(sessionId)
            .collection("messages")
            .whereEqualTo("senderRole", "dashboard")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("WebRtcClient", "signaling listener error", error)
                    return@addSnapshotListener
                }
                snapshot?.documentChanges?.forEach { change ->
                    if (change.type != com.google.firebase.firestore.DocumentChange.Type.ADDED) return@forEach
                    val doc = change.document
                    val type = doc.getString("type") ?: return@forEach
                    val payload = doc.get("payload") as? Map<*, *> ?: return@forEach

                    when (type) {
                        "answer" -> {
                            val sdp = payload["sdp"] as? String ?: return@forEach
                            peerConnection?.setRemoteDescription(object : SdpObserver {
                                override fun onCreateSuccess(p0: SessionDescription?) {}
                                override fun onSetSuccess() {}
                                override fun onCreateFailure(p0: String?) {}
                                override fun onSetFailure(p0: String?) {}
                            }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
                        }
                        "ice-candidate" -> {
                            val candidate = IceCandidate(
                                payload["sdpMid"] as? String ?: "",
                                ((payload["sdpMLineIndex"] as? Long)?.toInt()) ?: 0,
                                payload["candidate"] as? String ?: ""
                            )
                            peerConnection?.addIceCandidate(candidate)
                        }
                    }
                }
            }
    }

    fun stop() {
        signalingListener?.remove()
        signalingListener = null
        audioTrack?.dispose()
        audioSource?.dispose()
        peerConnection?.close()
        peerConnection = null
        audioTrack = null
        audioSource = null
    }
}

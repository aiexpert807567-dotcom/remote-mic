package com.yourdomain.remoteaudiomonitor.session

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

/**
 * Watches Firestore for new session requests targeting this paired device
 * and reacts to them. This is deliberately separate from WebRTC itself —
 * this class only tracks session *state* (requested/connecting/active/
 * ended/error); the actual audio transport is handled elsewhere once a
 * session reaches "connecting".
 *
 * Only ever watches sessions for this device's own deviceDocId — a device
 * never sees or reacts to another device's sessions, matching the
 * Firestore rules already in place.
 */
class SessionManager(private val deviceDocId: String) {

    private val firestore = FirebaseFirestore.getInstance()
    private var listenerRegistration: ListenerRegistration? = null

    interface SessionListener {
        fun onSessionRequested(sessionId: String)
        fun onSessionEnded(sessionId: String)
    }

    /**
     * Starts watching for sessions on this device. Call stopWatching()
     * when the app is backgrounded or destroyed to release the listener.
     */
    fun startWatching(listener: SessionListener) {
        stopWatching()

        listenerRegistration = firestore.collection("sessions")
            .whereEqualTo("deviceId", deviceDocId)
            .whereIn("state", listOf("requested", "connecting", "active"))
            .orderBy("requestedAt", Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) {
                    return@addSnapshotListener
                }
                for (change in snapshot.documentChanges) {
                    val doc = change.document
                    val state = doc.getString("state")
                    when (state) {
                        "requested" -> listener.onSessionRequested(doc.id)
                        "ended" -> listener.onSessionEnded(doc.id)
                    }
                }
            }
    }

    fun stopWatching() {
        listenerRegistration?.remove()
        listenerRegistration = null
    }

    /**
     * Acknowledges a requested session by moving it to "connecting".
     * This confirms the device received the request — actual WebRTC
     * connection setup happens in a later step, not here.
     */
    suspend fun acknowledgeSession(sessionId: String) {
        firestore.collection("sessions").document(sessionId)
            .update("state", "connecting")
    }

    suspend fun markSessionActive(sessionId: String) {
        firestore.collection("sessions").document(sessionId)
            .update("state", "active")
    }

    suspend fun markSessionError(sessionId: String, reason: String) {
        firestore.collection("sessions").document(sessionId)
            .update(
                mapOf(
                    "state" to "error",
                    "errorReason" to reason
                )
            )
    }
}

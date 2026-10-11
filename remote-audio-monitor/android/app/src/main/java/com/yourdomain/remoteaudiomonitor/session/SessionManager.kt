package com.yourdomain.remoteaudiomonitor.session

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query

class SessionManager(private val deviceDocId: String) {

    private val firestore = FirebaseFirestore.getInstance()
    private var listenerRegistration: ListenerRegistration? = null

    interface SessionListener {
        fun onSessionRequested(sessionId: String)
        fun onSessionEnded(sessionId: String)
        fun onListenerError(message: String) {}
    }

    fun startWatching(listener: SessionListener) {
        stopWatching()

        listenerRegistration = firestore.collection("sessions")
            .whereEqualTo("deviceId", deviceDocId)
            .whereIn("state", listOf("requested", "connecting", "active"))
            .orderBy("requestedAt", Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    listener.onListenerError("${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                for (change in snapshot.documentChanges) {
                    val doc = change.document
                    when (doc.getString("state")) {
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

    suspend fun acknowledgeSession(sessionId: String) {
        firestore.collection("sessions").document(sessionId).update("state", "connecting")
    }

    suspend fun markSessionActive(sessionId: String) {
        firestore.collection("sessions").document(sessionId).update("state", "active")
    }

    suspend fun markSessionError(sessionId: String, reason: String) {
        firestore.collection("sessions").document(sessionId)
            .update(mapOf("state" to "error", "errorReason" to reason))
    }

    suspend fun endSession(sessionId: String) {
        firestore.collection("sessions").document(sessionId)
            .update(mapOf("state" to "ended", "endedAt" to FieldValue.serverTimestamp()))
    }
}

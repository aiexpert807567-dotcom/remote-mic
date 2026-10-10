package com.yourdomain.remoteaudiomonitor.pairing

import android.content.Context
import android.os.Build
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Handles one-time device pairing and local persistence of the paired
 * device's identity. Pairing only ever happens once per physical device
 * (until the user removes it from the dashboard) — this class is
 * responsible for remembering that fact locally so the app never asks
 * for a pairing code again after the first successful pairing.
 */
class PairingManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("pairing_prefs", Context.MODE_PRIVATE)
    private val firestore = FirebaseFirestore.getInstance()

    companion object {
        private const val KEY_DEVICE_ID = "paired_device_id"
        private const val KEY_DEVICE_DOC_ID = "paired_device_doc_id"
    }

    /** True if this physical device has already completed pairing locally. */
    fun isPaired(): Boolean {
        return prefs.getString(KEY_DEVICE_DOC_ID, null) != null
    }

    fun getPairedDeviceDocId(): String? = prefs.getString(KEY_DEVICE_DOC_ID, null)

    /** A stable local identifier for this physical device, generated once. */
    private fun getOrCreateLocalDeviceId(): String {
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    sealed class PairingResult {
        data class Success(val deviceDocId: String) : PairingResult()
        data class Failure(val message: String) : PairingResult()
    }

    /**
     * Attempts to claim a pairing code entered by the user. Requires the
     * user to already be signed in (Firestore rules enforce ownership).
     * On success, creates a `devices` document and a local record so this
     * device is never asked to pair again.
     */
    suspend fun claimPairingCode(code: String, deviceDisplayName: String): PairingResult {
        val user = FirebaseAuth.getInstance().currentUser
            ?: return PairingResult.Failure("Not signed in")

        return try {
            val tokensQuery = firestore.collection("pairingTokens")
                .whereEqualTo("ownerId", user.uid)
                .whereEqualTo("token", code.trim().uppercase())
                .whereEqualTo("used", false)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(1)
                .get()
                .await()

            if (tokensQuery.isEmpty) {
                return PairingResult.Failure("Invalid or already-used code")
            }

            val tokenDoc = tokensQuery.documents.first()
            val expiresAt = tokenDoc.getTimestamp("expiresAt")
            if (expiresAt == null || expiresAt.toDate().before(java.util.Date())) {
                return PairingResult.Failure("This code has expired")
            }

            // Mark the token used first — rules only allow the `used` field
            // to change, nothing else, preventing tampering.
            firestore.collection("pairingTokens").document(tokenDoc.id)
                .update("used", true)
                .await()

            val localDeviceId = getOrCreateLocalDeviceId()
            val deviceData = hashMapOf(
                "ownerId" to user.uid,
                "localDeviceId" to localDeviceId,
                "displayName" to deviceDisplayName,
                "status" to "online",
                "pairedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "lastSeenAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )

            val deviceRef = firestore.collection("devices").add(deviceData).await()

            prefs.edit()
                .putString(KEY_DEVICE_DOC_ID, deviceRef.id)
                .apply()

            PairingResult.Success(deviceRef.id)
        } catch (e: Exception) {
            PairingResult.Failure(e.message ?: "Pairing failed")
        }
    }

    /** Default display name suggestion, editable by the user before pairing. */
    fun suggestedDeviceName(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }

    /** Clears local pairing state — used if the user wants to re-pair this device. */
    fun forgetLocalPairing() {
        prefs.edit().clear().apply()
    }

    /**
     * Updates lastSeenAt and status on this device's Firestore document.
     * Called periodically while the app is in the foreground and once when
     * the microphone service starts/stops, so the dashboard reflects real
     * device state rather than a stale value from pairing time.
     */
    suspend fun sendHeartbeat(status: String = "online") {
        val deviceDocId = getPairedDeviceDocId() ?: return
        try {
            firestore.collection("devices").document(deviceDocId)
                .update(
                    mapOf(
                        "status" to status,
                        "lastSeenAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                    )
                )
                .await()
        } catch (e: Exception) {
            // Heartbeat failures are non-fatal — the device will simply
            // show as stale/offline on the dashboard until the next
            // successful heartbeat. Never crash the app over this.
        }
    }
}

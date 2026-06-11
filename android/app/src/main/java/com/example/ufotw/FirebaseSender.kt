package com.first.ufotw

import android.util.Log
import com.google.firebase.database.FirebaseDatabase

class FirebaseSender(private val roomCode: String) : BleSender {

    companion object {
        private const val TAG = "FirebaseSender"
    }

    /** Firebaseへの送信に失敗した際に通知するコールバック */
    var onSendFailed: (() -> Unit)? = null

    private val ref = FirebaseDatabase
        .getInstance("https://ufotwcontrol-default-rtdb.asia-southeast1.firebasedatabase.app")
        .getReference("rooms/$roomCode/command")

    override fun send(bytes: ByteArray) {
        if (bytes.size < 3) return
        val speed1 = bytes[1].toInt() and 0x7F
        val dir1   = (bytes[1].toInt() ushr 7) and 0x01
        val speed2 = bytes[2].toInt() and 0x7F
        val dir2   = (bytes[2].toInt() ushr 7) and 0x01
        Log.d(TAG, "Sending: s1=$speed1 d1=$dir1 s2=$speed2 d2=$dir2")
        ref.setValue(
            mapOf("speed1" to speed1, "dir1" to dir1,
                  "speed2" to speed2, "dir2" to dir2)
        ).addOnFailureListener { e ->
            Log.e(TAG, "Firebase write failed: ${e.message}")
            onSendFailed?.invoke()
        }
    }
}

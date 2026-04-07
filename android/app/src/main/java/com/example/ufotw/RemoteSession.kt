package com.first.ufotw

import android.util.Log
import com.google.firebase.database.*

class RemoteSession(private val roomCode: String) {

    private val ref = FirebaseDatabase
        .getInstance("https://ufotwcontrol-default-rtdb.asia-southeast1.firebasedatabase.app")
        .getReference("rooms/$roomCode/command")

    private var listener: ValueEventListener? = null

    /** ローカルモード用：Firebase のコマンドを受信してコールバック */
    fun startListening(onCommand: (speed1: Int, dir1: Int, speed2: Int, dir2: Int) -> Unit) {
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val speed1 = snapshot.child("speed1").getValue(Int::class.java) ?: return
                val dir1   = snapshot.child("dir1").getValue(Int::class.java)   ?: return
                val speed2 = snapshot.child("speed2").getValue(Int::class.java) ?: return
                val dir2   = snapshot.child("dir2").getValue(Int::class.java)   ?: return
                Log.d(TAG, "Command received: s1=$speed1 d1=$dir1 s2=$speed2 d2=$dir2")
                onCommand(speed1, dir1, speed2, dir2)
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase listener cancelled: ${error.message}")
            }
        }
        ref.addValueEventListener(l)
        listener = l
    }

    fun stopListening() {
        listener?.let { ref.removeEventListener(it) }
        listener = null
    }

    companion object {
        private const val TAG = "RemoteSession"

        private val adjectives = listOf("SAKURA","FUJI","KAZE","HOSHI","NAMI","SORA","TSUKI","HANA")
        private val nouns      = listOf("TIGER","EAGLE","STORM","RIVER","FLAME","SNOW","MOON","WAVE")

        fun generateRoomCode(): String {
            val adj = adjectives.random()
            val num = (1000..9999).random()
            return "$adj-$num"
        }
    }
}

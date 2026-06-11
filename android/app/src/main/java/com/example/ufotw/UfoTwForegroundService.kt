package com.first.ufotw

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class UfoTwForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "ufotw_channel"
        private const val NOTIF_ID = 1
        private const val EXTRA_ROOM_CODE = "room_code"

        fun start(context: Context, roomCode: String) {
            val intent = Intent(context, UfoTwForegroundService::class.java)
                .putExtra(EXTRA_ROOM_CODE, roomCode)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UfoTwForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "UFO TW", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "バックグラウンド接続維持" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getStringExtra(EXTRA_ROOM_CODE) ?: ""
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UFO TW 接続中")
            .setContentText("ルームコード: $code")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIF_ID, notif)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

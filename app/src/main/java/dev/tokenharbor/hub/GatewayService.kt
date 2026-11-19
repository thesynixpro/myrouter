package dev.tokenharbor.hub

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class GatewayService : Service() {
    override fun onCreate() {
        super.onCreate()
        val channelId = "token_harbor_gateway"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Token Harbor Gateway", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Token Harbor Hub")
            .setContentText("Gateway service is active")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true).build()
        startForeground(1001, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}

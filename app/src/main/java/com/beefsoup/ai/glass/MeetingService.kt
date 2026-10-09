package com.beefsoup.ai.glass

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

class MeetingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "會議錄製", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as MeetingApplication).runtime.stop()
            return START_NOT_STICKY
        }
        val stop = PendingIntent.getService(this, 1,
            Intent(this, MeetingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("會議音訊錄製中")
            .setContentText("點選查看逐字稿；可隨時停止錄製")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止錄製", stop).build())
            .setOngoing(true).build()
        startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_START = "com.beefsoup.ai.glass.START"
        const val ACTION_STOP = "com.beefsoup.ai.glass.STOP"
        private const val CHANNEL = "meeting-recording"
    }
}

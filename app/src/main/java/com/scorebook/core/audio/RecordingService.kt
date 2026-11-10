package com.scorebook.core.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.scorebook.MainActivity

/**
 * 녹음 중에 다른 앱으로 나가도 마이크가 막히지 않도록 띄워 두는 포그라운드 서비스.
 * 녹음 자체는 [Recorder] 가 하고, 이 서비스는 "녹음 중" 알림만 유지한다.
 */
class RecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, "녹음", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("녹음 중")
            .setContentText("연습을 녹음하고 있습니다. 눌러서 돌아가기")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    // 최근 앱 목록에서 앱을 밀어 없애면 알림도 함께 내린다
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
    }
}

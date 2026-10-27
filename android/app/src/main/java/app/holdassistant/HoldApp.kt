package app.holdassistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import app.holdassistant.data.CallLogStore
import app.holdassistant.data.LineStore
import app.holdassistant.data.Settings

class HoldApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var lines: LineStore
        private set
    lateinit var callLogs: CallLogStore
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        lines = LineStore(this)
        callLogs = CallLogStore(this)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALL, "Call in progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Live hold status and transcript"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Someone picked up", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A person or a menu needs you"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build(),
                )
            },
        )
    }

    companion object {
        const val CHANNEL_CALL = "call"
        const val CHANNEL_ALERT = "alert"

        lateinit var instance: HoldApp
            private set
    }
}

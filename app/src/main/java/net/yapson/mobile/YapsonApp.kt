package net.yapson.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import net.yapson.mobile.utils.Prefs

class YapsonApp : Application() {

    companion object {
        const val CHANNEL_ID = "yapson_service"
        const val CHANNEL_NAME = "Yapson Service"
        const val NOTIF_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        // Initialiser Prefs EN PREMIER — avant tout le reste
        Prefs.init(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Service Yapson Mobile Money"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}

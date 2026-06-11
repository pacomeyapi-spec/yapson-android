package net.yapson.mobile.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.yapson.mobile.api.ApiClient
import net.yapson.mobile.utils.Prefs

class NotificationListenerService : NotificationListenerService() {

    private val TAG = "WaveListener"
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return

        // Vérifier si c'est un package Wave surveillé
        val wavePackages = Prefs.wavePackages.split(",").map { it.trim() }
        if (!wavePackages.contains(pkg)) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        if (text.isBlank()) return

        Log.d(TAG, "Notification Wave: [$pkg] $title | $text")

        // Envoyer au backend
        scope.launch {
            val opId = Prefs.attributableOperationId()
            val success = ApiClient.sendNotification(opId, pkg, title, text)
            Log.d(TAG, "Notif envoyée: $success")
        }
    }
}

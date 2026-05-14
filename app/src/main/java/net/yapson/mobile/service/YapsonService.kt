package net.yapson.mobile.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import net.yapson.mobile.R
import net.yapson.mobile.YapsonApp
import net.yapson.mobile.api.ApiClient
import net.yapson.mobile.model.Operation
import net.yapson.mobile.ui.MainActivity
import net.yapson.mobile.utils.Prefs
import net.yapson.mobile.utils.UssdHelper

class YapsonService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    companion object {
        private const val TAG = "YapsonService"
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        var isRunning = false
        var currentOperation: Operation? = null
        var lastLog: String = "Service démarré"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.d(TAG, "Service créé")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // CRITIQUE: configurer ApiClient avec les credentials stockés
                // Le service tourne en background, il doit configurer lui-même ApiClient
                Prefs.init(applicationContext)
                if (Prefs.isConfigured()) {
                    ApiClient.configure(Prefs.backendUrl, Prefs.deviceToken)
                    log("🔗 API configurée: ${Prefs.backendUrl}")
                } else {
                    log("⚠️ Backend non configuré — allez dans Config")
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Acquérir WakeLock pour éviter que Android endorme le service
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Yapson:UssdWakeLock"
                ).also {
                    it.acquire(30 * 60 * 1000L) // Max 30 minutes
                }
                log("🔒 WakeLock acquis")

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    startForeground(
                        YapsonApp.NOTIF_ID,
                        buildNotification("En attente d'opérations..."),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(YapsonApp.NOTIF_ID, buildNotification("En attente d'opérations..."))
                }
                startPolling()
            }
        }
        return START_STICKY
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var heartbeatCounter = 0

            while (isActive) {
                try {
                    // Heartbeat toutes les 60s
                    if (heartbeatCounter++ % (60 / Prefs.pollInterval) == 0) {
                        ApiClient.heartbeat()
                    }

                    // Chercher une opération PENDING
                    val pending = ApiClient.getPendingOperations()
                    if (pending.isNotEmpty()) {
                        val op = pending.first()
                        log("📋 Opération trouvée: ${op.type} ${op.amount}F via ${op.operator}")
                        processOperation(op)
                    } else {
                        log("⏳ En attente... (${Prefs.backendUrl.takeLast(20)})")
                    }

                    updateNotification(lastLog)
                } catch (e: Exception) {
                    log("❌ Erreur: ${e.message}")
                    Log.e(TAG, "Poll error", e)
                }

                delay(Prefs.pollInterval * 1000L)
            }
        }
    }

    private suspend fun processOperation(op: Operation) {
        // Prendre en charge l'opération
        val taken = ApiClient.takeOperation(op.id)
        if (taken == null) {
            log("⚠️ Opération déjà prise en charge")
            return
        }

        currentOperation = taken
        Prefs.currentOperationId = taken.id
        log("▶️ Traitement: ${taken.type} ${taken.amount}F ${taken.operator} → ${taken.phoneNumber}")

        // Priorité aux étapes multi-étapes (nouveau format)
        val steps = taken.ussdSteps?.filter { it.isNotBlank() }.let { s ->
            // Si ussdSteps est vide mais ussdCode contient des étapes séparées par |
            if (s.isNullOrEmpty() && !taken.ussdCode.isNullOrBlank()) {
                taken.ussdCode.split("|").filter { it.isNotBlank() }
            } else {
                s
            }
        }

        when {
            // Étapes multiples disponibles
            !steps.isNullOrEmpty() -> {
                log("📞 Séquence USSD: ${steps.size} étapes")
                withContext(Dispatchers.Main) {
                    UssdHelper.executeSteps(
                        ctx = applicationContext,
                        steps = steps,
                        onStep = { num, code -> log("📲 Étape $num/${steps.size}: $code") },
                        onDone = { log("✅ Séquence USSD terminée") },
                        onError = { err -> log("❌ Erreur USSD: $err") }
                    )
                }
            }
            else -> {
                log("⚠️ Pas de code USSD pour cette opération")
            }
        }
    }

    private fun log(msg: String) {
        lastLog = msg
        Log.d(TAG, msg)
        // Broadcaster pour l'UI
        sendBroadcast(Intent("net.yapson.mobile.LOG").putExtra("msg", msg))
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(YapsonApp.NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, YapsonApp.CHANNEL_ID)
            .setContentTitle("Yapson Mobile")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        currentOperation = null
        scope.cancel()
        // Libérer le WakeLock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "🔓 WakeLock libéré")
            }
        }
        Log.d(TAG, "Service arrêté")
        super.onDestroy()
    }
}

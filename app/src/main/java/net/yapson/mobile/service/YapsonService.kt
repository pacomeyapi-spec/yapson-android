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
import net.yapson.mobile.model.AutoConfig
import net.yapson.mobile.ui.MainActivity
import net.yapson.mobile.utils.Prefs
import net.yapson.mobile.utils.UssdRunner
import net.yapson.mobile.utils.SmsReader
import net.yapson.mobile.utils.Ntfy
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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

                    // Mode auto-dépôt : si activé côté dashboard, l'appareil dépose tout seul
                    // le solde lu dans le dernier SMS +454, en boucle (pas de poll d'opérations).
                    val auto = ApiClient.getAutoConfig()
                    if (auto != null && auto.enabled) {
                        runAutoDepot(auto)
                        updateNotification(lastLog)
                        delay(auto.intervalSec.coerceAtLeast(15) * 1000L)
                        continue
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

        // ── Canal APP (Wave Business / Orange Max it via accessibilité) ──
        if (taken.channel.equals("APP", ignoreCase = true)) {
            log("📱 Canal APP (${taken.operator})")
            val res = AppRunner.run(taken)
            if (res.success) log("✅ APP réussi") else log("❌ APP échec: ${res.message}")
            ApiClient.report(taken.id, res.success, res.message ?: "", if (res.success) null else res.message, res.ref)
            currentOperation = null
            Prefs.currentOperationId = ""
            return
        }

        // Priorité aux étapes multi-étapes (nouveau format)
        val steps = taken.ussdSteps?.filter { it.isNotBlank() }.let { s ->
            // Si ussdSteps est vide mais ussdCode contient des étapes séparées par |
            if (s.isNullOrEmpty() && !taken.ussdCode.isNullOrBlank()) {
                taken.ussdCode.split("|").filter { it.isNotBlank() }
            } else {
                s
            }
        }

        if (steps.isNullOrEmpty()) {
            log("⚠️ Pas de code USSD pour cette opération")
            ApiClient.report(taken.id, false, "", "Séquence USSD absente")
            currentOperation = null
            Prefs.currentOperationId = ""
            return
        }

        log("📞 Séquence USSD: ${steps.size} étapes")
        // Pilotage via le service d'accessibilité (vraie navigation du menu USSD).
        // On attend la fin (succès / échec / timeout) avant de reprendre le poll.
        val result = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<UssdRunner.UssdResult> { cont ->
                UssdRunner.runSteps(applicationContext, taken.id, steps, simSlot = taken.simSlot) { r ->
                    if (cont.isActive) cont.resume(r)
                }
            }
        }

        if (result.success) {
            log("✅ USSD réussi")
        } else {
            log("❌ USSD échec: ${result.error ?: result.finalText.take(60)}")
        }
        // Remonter le résultat au backend (la plateforme met à jour la transaction).
        ApiClient.report(taken.id, result.success, result.finalText, result.error)

        currentOperation = null
        Prefs.currentOperationId = ""
    }

    /**
     * Un cycle d'auto-dépôt :
     *  - 1er lancement : dépôt sonde (probeAmount, ex 200 F) pour révéler le solde ;
     *  - ensuite : lit le solde du dernier SMS +454 et dépose tout (multiple de 10 F).
     * NTFY est envoyé sur tout échec, avec la raison tirée du SMS +454.
     */
    private suspend fun runAutoDepot(cfg: AutoConfig) {
        // SIM choisie dans le dashboard : on lit le solde UNIQUEMENT sur cette SIM (subscriptionId),
        // sinon avec deux SIM du même opérateur on lirait le solde du mauvais compte.
        val rawSubId = SmsReader.subIdForSlot(applicationContext, cfg.simSlot)
        // Certains téléphones (Huawei/EMUI) ne remplissent pas SUBSCRIPTION_ID :
        // on retombe alors sur le slot, puis sur une lecture non filtrée.
        val subId = SmsReader.effectiveSubId(applicationContext, cfg.simSlot, rawSubId)
        if (subId != rawSubId) log("ℹ️ SMS: SUBSCRIPTION_ID inexploitable (${rawSubId}) → lecture ${if (subId < 0) "non filtrée" else "par slot $subId"}")
        val latest = SmsReader.lastFrom(applicationContext, "454", subId)
        val tsBefore = latest?.ts ?: 0L

        val amount: Int
        val isProbe: Boolean
        if (!Prefs.autoProbeDone || Prefs.autoProbeSlot != cfg.simSlot) {
            // Si la sonde échoue en boucle (SMS illisibles), on N'ARRÊTE PAS l'auto-dépôt :
            // l'argent doit continuer d'être reversé dès que la lecture redevient possible.
            // On ESPACE simplement les tentatives (15 min au lieu de chaque cycle) pour ne
            // pas brûler 200 F toutes les 2 minutes, et on alerte UNE seule fois.
            if (Prefs.autoProbeFails >= 3) {
                val depuis = System.currentTimeMillis() - Prefs.autoLastProbeAt
                if (!Prefs.autoProbeAlerted) {
                    Prefs.autoProbeAlerted = true
                    Ntfy.push(cfg.ntfyTopic, "Yapson auto-depot",
                        "Sondes sans SMS +454 lisible: nouvelles tentatives espacees a 15 min (au lieu de 2). L'auto-depot CONTINUE. Verifie l'autorisation SMS de l'app.")
                }
                if (depuis < 15 * 60_000L) {
                    log("⏳ Auto-dépôt: sonde espacée (${Prefs.autoProbeFails} échecs) — prochaine dans ${((15 * 60_000L - depuis) / 60_000L) + 1} min")
                    return
                }
            }
            Prefs.autoLastProbeAt = System.currentTimeMillis()
            amount = (cfg.probeAmount.coerceAtLeast(10) / 10) * 10
            isProbe = true
            log("⚡ Auto-dépôt: sonde initiale ${amount}F → ${cfg.destination}")
        } else {
            if (latest == null) { log("⏳ Auto-dépôt: aucun SMS +454"); return }
            if (latest.ts <= Prefs.autoLastSmsTs) { log("⏳ Auto-dépôt: pas de nouveau SMS +454"); return }
            val bal = SmsReader.parseSolde(latest.body)
            if (bal == null) {
                log("⚠️ Auto-dépôt: solde illisible")
                Ntfy.push(cfg.ntfyTopic, "Yapson auto-depot", "Solde illisible dans le SMS +454: ${latest.body.take(120)}")
                Prefs.autoLastSmsTs = latest.ts
                return
            }
            var a = ((bal / 10) * 10).toInt()
            if (cfg.maxAmount > 0) a = minOf(a, (cfg.maxAmount / 10) * 10)
            if (a < cfg.minAmount || a < 10) {
                log("⏳ Auto-dépôt: solde $bal → rien à déposer (min ${cfg.minAmount})")
                Prefs.autoLastSmsTs = latest.ts
                return
            }
            amount = a
            isProbe = false
            log("⚡ Auto-dépôt: solde $bal → dépôt ${amount}F → ${cfg.destination}")
            Prefs.autoLastSmsTs = latest.ts // marque ce SMS comme traité (anti double-dépôt)
        }

        val op = ApiClient.createAutoDepot(amount)
        if (op == null || op.ussdSteps.isNullOrEmpty()) {
            log("❌ Auto-dépôt: création opération échouée")
            Ntfy.push(cfg.ntfyTopic, "Yapson auto-depot ECHEC", "Création du dépôt ${amount}F échouée (modèle USSD ? SIM ?)")
            return
        }
        Prefs.currentOperationId = op.id
        val result = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<UssdRunner.UssdResult> { cont ->
                UssdRunner.runSteps(applicationContext, op.id, op.ussdSteps!!, simSlot = op.simSlot) { r ->
                    if (cont.isActive) cont.resume(r)
                }
            }
        }
        ApiClient.report(op.id, result.success, result.finalText, result.error)
        Prefs.currentOperationId = ""
        if (!result.success) {
            log("❌ Auto-dépôt: USSD échec ${result.error}")
            Ntfy.push(cfg.ntfyTopic, "Yapson auto-depot ECHEC", "USSD ${amount}F: ${result.error ?: result.finalText.take(100)}")
            return
        }

        // Attendre le SMS +454 de confirmation (succès/échec + nouveau solde)
        // On IGNORE les paiements clients qui arrivent pendant l'opération : plusieurs
        // dépôts utilisateurs tombent souvent en pleine exécution de l'USSD, et le
        // premier SMS venu était pris pour la confirmation → fausse alerte ECHEC.
        // On balaie tous les SMS nouveaux (pas seulement le dernier) : la vraie
        // confirmation peut être suivie d'un paiement qui la masquerait.
        var confirm: SmsReader.Sms? = null
        var paiementsIgnores = 0
        val deadline = System.currentTimeMillis() + 90_000L
        while (System.currentTimeMillis() < deadline) {
            val nouveaux = SmsReader.listFrom(applicationContext, "454", subId, tsBefore)
            val c = nouveaux.firstOrNull { !SmsReader.isIncomingTransfer(it.body) }
            if (c != null) { confirm = c; break }
            if (nouveaux.size > paiementsIgnores) {
                paiementsIgnores = nouveaux.size
                log("⏳ Auto-dépôt: $paiementsIgnores paiement(s) client pendant l'opération — ignoré(s)")
            }
            delay(3000)
        }
        if (confirm == null) {
            if (isProbe) Prefs.autoProbeFails = Prefs.autoProbeFails + 1
            SmsReader.resetSubIdCache()   // re-détecte le mode de lecture au prochain essai
            log("⚠️ Auto-dépôt: pas de SMS +454 de confirmation (timeout)")
            // Diagnostic : dit précisément si c'est le filtre SIM qui masque les SMS.
            val diag = SmsReader.diagnostic(applicationContext, "454", rawSubId, cfg.simSlot)
            log("🔎 $diag")
            Ntfy.push(cfg.ntfyTopic, "Yapson diagnostic SMS", diag)
            Ntfy.push(cfg.ntfyTopic, "Yapson auto-depot", "Dépôt ${amount}F: aucun SMS +454 reçu (timeout 90s)")
            return
        }
        if (SmsReader.isSuccess(confirm.body)) {
            log("✅ Auto-dépôt: ${amount}F confirmé")
            if (isProbe) { Prefs.autoProbeDone = true; Prefs.autoProbeSlot = cfg.simSlot; Prefs.autoLastSmsTs = tsBefore; Prefs.autoProbeFails = 0; Prefs.autoProbeAlerted = false } // la confirmation (plus récente) sera balayée au prochain cycle
        } else {
            log("❌ Auto-dépôt: échec — ${confirm.body.take(80)}")
            Ntfy.push(cfg.ntfyTopic, "Yapson auto-depot ECHEC", confirm.body) // raison reçue par SMS
            Prefs.autoLastSmsTs = confirm.ts
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

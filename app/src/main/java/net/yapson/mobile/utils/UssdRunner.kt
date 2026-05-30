package net.yapson.mobile.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import net.yapson.mobile.service.UssdAccessibilityService

/**
 * Orchestrateur USSD robuste (porté de YAPLESS, adapté au modèle Yapson).
 *
 * Le backend envoie déjà la séquence COMPLÈTE dans `Operation.ussdSteps`
 * (ex: ["*144#", "1", "0788334833", "13200", "1234", "1"]) — PIN inclus.
 *   - steps[0]  = code USSD initial → composé via ACTION_CALL
 *   - steps[1..] = réponses successives → SAISIES dans la boîte de dialogue
 *                  opérateur via le service d'accessibilité (la vraie méthode,
 *                  contrairement à sendUssdRequest qui ouvrait une nouvelle
 *                  session à chaque fois).
 *
 * Gère : détection des mots-clés d'erreur opérateur, interception des codes
 * anti-fraude (Orange Money & co.), watchdog de timeout, notifications, et le
 * scan multi-fenêtres (indispensable sur Tecno/Infinix/itel — Transsion).
 */
object UssdRunner {

    data class UssdResult(
        val operationId: String,
        val success: Boolean,
        val finalText: String,
        val error: String? = null
    )

    class Session(
        val operationId: String,
        val inputs: ArrayDeque<String>,
        val errorKeywords: List<String>,
        val onFinish: (UssdResult) -> Unit
    ) {
        @Volatile var lastActivityAt: Long = System.currentTimeMillis()
        @Volatile var finished: Boolean = false
        val transcript = StringBuilder()
        @Volatile var lastHandledHash: Int = 0
    }

    @Volatile var session: Session? = null; private set
    @Volatile var dialStartedAt: Long = 0L; private set
    @Volatile private var lastCtx: Context? = null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private const val SESSION_TIMEOUT_MS = 90_000L
    private const val WATCHDOG_TICK_MS = 2_000L
    private const val USSD_CHANNEL_ID = "yapson-ussd-active"
    private const val USSD_NOTIF_ID = 8421

    private val DEFAULT_ERROR_KEYWORDS = listOf(
        "indisponible", "indisponble", "service indisponible",
        "reessayer plus tard", "réessayer plus tard",
        "invalide", "invalid", "echec", "échec", "failed", "erreur",
        "insufficient", "solde insuffisant", "solde insufisant",
        "limite atteinte", "limit reached", "depass", "saturé", "sature",
        "bloqué", "bloque", "compte bloqu", "suspendu", "compte suspendu",
        "compte inactif", "compte fermé", "compte ferme",
        "non autorisé", "non autorise", "not allowed", "interdit", "forbidden",
        "refusé", "refuse de", "rejet de",
        "pin incorrect", "code pin incorrect", "code incorrect", "wrong pin",
        "destinataire introuvable", "destinataire inexistant", "destinataire invalide",
        "numero introuvable", "numéro introuvable", "numero invalide", "numéro invalide",
        "transaction annulée", "transaction annulee", "annulé par",
        "session expir", "transaction expir", "code expir"
    )

    fun isBusy(): Boolean = session?.let { !it.finished } ?: false

    /**
     * Exécute une séquence USSD complète fournie par le backend.
     * @param steps liste ordonnée ; steps[0] est composé, le reste est saisi.
     * @param simSlot slot SIM (0 par défaut).
     */
    fun runSteps(
        ctx: Context,
        operationId: String,
        steps: List<String>,
        simSlot: Int = 0,
        errorKeywords: List<String> = DEFAULT_ERROR_KEYWORDS,
        onFinish: (UssdResult) -> Unit
    ) {
        if (isBusy()) {
            onFinish(UssdResult(operationId, false, "", "Une session USSD est déjà en cours"))
            return
        }
        val clean = steps.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) {
            onFinish(UssdResult(operationId, false, "", "Séquence USSD vide"))
            return
        }
        if (!UssdAccessibilityService.isConnected) {
            onFinish(UssdResult(operationId, false, "", "Service d'accessibilité non activé (Réglages → Accessibilité → Yapson USSD)"))
            return
        }

        val dialCode = clean.first()
        val inputs = ArrayDeque<String>()
        for (i in 1 until clean.size) inputs.add(clean[i])

        val s = Session(operationId, inputs, errorKeywords, onFinish)
        session = s
        lastCtx = ctx.applicationContext
        startWatchdog(s)

        UssdLog.clear()
        UssdLog.add("🚀 Session USSD — op=$operationId sim=$simSlot")
        UssdLog.add("📞 Dial: $dialCode")
        if (inputs.isEmpty()) UssdLog.add("⚡ Une seule étape — on attend la réponse opérateur")
        else UssdLog.add("📋 Étapes à saisir: ${inputs.toList()}")

        try { notifyStart(ctx.applicationContext) } catch (_: Exception) {}

        dialStartedAt = System.currentTimeMillis()
        val ok = dialUssd(ctx, dialCode, simSlot)
        UssdLog.add(if (ok) "✓ Dial envoyé — attente du dialogue opérateur…" else "❌ Dial impossible (permission CALL_PHONE ?)")
        if (!ok) finish(s, UssdResult(operationId, false, "", "Impossible de composer le code (permission CALL_PHONE ?)"))
    }

    /** Appelé par le service d'accessibilité à chaque dialogue USSD lu. */
    fun onUssdDialog(promptText: String, hasInputField: Boolean, feed: (input: String?, submit: Boolean, dismiss: Boolean) -> Unit) {
        val s = session ?: return
        if (s.finished) return
        s.lastActivityAt = System.currentTimeMillis()

        val hash = promptText.hashCode()
        if (hash == s.lastHandledHash && promptText.isNotEmpty()) { feed(null, false, false); return }
        s.lastHandledHash = hash

        if (promptText.isNotEmpty()) {
            if (s.transcript.isNotEmpty()) s.transcript.append("\n---\n")
            s.transcript.append(promptText)
        }

        val matched = matchedKeyword(promptText, s.errorKeywords)
        if (matched != null) {
            UssdLog.add("🛑 Erreur opérateur détectée: '$matched'")
            feed(null, false, true)
            finish(s, UssdResult(s.operationId, false, s.transcript.toString(), "Réponse opérateur : '$matched'"))
            return
        }

        // Anti-fraude : code aléatoire à retaper, sans consommer une étape.
        if (hasInputField) {
            val confirmCode = extractConfirmationCode(promptText)
            if (confirmCode != null) {
                UssdLog.add("🛡️ Code anti-fraude détecté: $confirmCode")
                feed(confirmCode, true, false)
                return
            }
        }

        if (hasInputField && s.inputs.isNotEmpty()) {
            val next = s.inputs.removeFirst()
            UssdLog.add("➡️ Saisie: '$next' (reste ${s.inputs.size})")
            feed(next, true, false)
        } else {
            val success = !containsAny(promptText, s.errorKeywords)
            UssdLog.add("🏁 Fin de séquence (succès=$success)")
            feed(null, false, true)
            finish(s, UssdResult(s.operationId, success, s.transcript.toString()))
        }
    }

    private fun finish(s: Session, result: UssdResult) {
        if (s.finished) return
        s.finished = true
        if (session === s) { session = null; dialStartedAt = 0L }
        try { lastCtx?.let { notifyEnd(it, result.success, if (result.success) result.finalText else (result.error ?: result.finalText)) } } catch (_: Exception) {}
        try { s.onFinish(result) } catch (_: Exception) {}
    }

    private fun startWatchdog(s: Session) {
        scope.launch {
            while (!s.finished) {
                delay(WATCHDOG_TICK_MS)
                if (System.currentTimeMillis() - s.lastActivityAt > SESSION_TIMEOUT_MS) {
                    finish(s, UssdResult(s.operationId, false, s.transcript.toString(), "Timeout USSD (aucune réponse)"))
                    break
                }
            }
        }
    }

    private fun dialUssd(ctx: Context, code: String, simSlot: Int): Boolean {
        return try {
            val uri = Uri.parse("tel:" + Uri.encode(code))
            val intent = Intent(Intent.ACTION_CALL, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            attachSimAccount(ctx, intent, simSlot)
            ctx.startActivity(intent)
            true
        } catch (_: SecurityException) { false } catch (_: Exception) { false }
    }

    private fun attachSimAccount(ctx: Context, intent: Intent, simSlot: Int) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            @Suppress("MissingPermission")
            val accounts = tm.callCapablePhoneAccounts
            if (accounts != null && accounts.size > simSlot) {
                intent.putExtra(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accounts[simSlot])
            }
        } catch (_: SecurityException) {} catch (_: Exception) {}
    }

    private fun containsAny(text: String, keywords: List<String>): Boolean {
        if (text.isEmpty() || keywords.isEmpty()) return false
        val lower = text.lowercase()
        return keywords.any { lower.contains(it.lowercase()) }
    }

    private fun matchedKeyword(text: String, keywords: List<String>): String? {
        if (text.isEmpty() || keywords.isEmpty()) return null
        val lower = text.lowercase()
        return keywords.firstOrNull { lower.contains(it.lowercase()) }
    }

    private fun extractConfirmationCode(text: String): String? {
        if (text.isEmpty()) return null
        val patterns = listOf(
            Regex("""compose[zr]?\s+(\d{2,5})\s+puis""", RegexOption.IGNORE_CASE),
            Regex("""tape[zr]?\s+(\d{2,5})\s+(?:puis|et|pour)""", RegexOption.IGNORE_CASE),
            Regex("""entre[zr]?\s+(\d{2,5})\s+(?:puis|et|pour)""", RegexOption.IGNORE_CASE),
            Regex("""sais(?:ir|issez)\s+(\d{2,5})\s+(?:puis|et|pour)""", RegexOption.IGNORE_CASE),
            Regex("""(?:code\s+(?:de\s+)?(?:confirmation|verification|sécurité)\s*:?\s*)(\d{2,5})""", RegexOption.IGNORE_CASE),
            Regex("""(\d{2,5})\s+puis\s+(?:ok|envoyer|valider|confirmer)""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) { p.find(text)?.let { return it.groupValues[1] } }
        return null
    }

    // ── Notifications ────────────────────────────────────────────────────────
    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(USSD_CHANNEL_ID) != null) return
        nm.createNotificationChannel(NotificationChannel(USSD_CHANNEL_ID, "Paiement Mobile Money", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Affiche l'état d'un paiement USSD en cours."; setShowBadge(false)
        })
    }

    private fun notifyStart(ctx: Context) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif: Notification = NotificationCompat.Builder(ctx, USSD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("Opération USSD en cours…")
            .setContentText("Composition Mobile Money…")
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_HIGH).setDefaults(0).build()
        nm.notify(USSD_NOTIF_ID, notif)
    }

    private fun notifyEnd(ctx: Context, success: Boolean, message: String) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val short = (message.ifBlank { if (success) "OK" else "Échec" }).take(160)
        val notif = NotificationCompat.Builder(ctx, USSD_CHANNEL_ID)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_phone_call else android.R.drawable.stat_notify_error)
            .setContentTitle(if (success) "Opération réussie" else "Opération échouée")
            .setContentText(short)
            .setStyle(NotificationCompat.BigTextStyle().bigText(short))
            .setOngoing(false).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(if (success) 0xFF059669.toInt() else 0xFFDC2626.toInt()).build()
        nm.notify(USSD_NOTIF_ID, notif)
    }
}

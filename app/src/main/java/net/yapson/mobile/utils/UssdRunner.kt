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
        val opType: String,                 // "DEPOT" ou "RETRAIT"
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

    // ── Sélection SIM par slot physique (piloté via SubscriptionManager + sélecteur d'accessibilité)
    @Volatile var desiredSimSlot: Int = -1; private set
    @Volatile var simPickerDone: Boolean = false
    @Volatile private var desiredSimNames: List<String> = emptyList()
    fun simNameHints(): List<String> = desiredSimNames

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
        opType: String = "DEPOT",
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

        val s = Session(operationId, inputs, errorKeywords, opType, onFinish)
        session = s
        lastCtx = ctx.applicationContext
        desiredSimSlot = simSlot
        simPickerDone = false
        desiredSimNames = simNamesForSlot(ctx, simSlot)
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

        // Verdict final de l'opérateur : on conclut TOUT DE SUITE, même s'il restait
        // des étapes à saisir (ex. plafond atteint annoncé en cours de séquence).
        val verdict = terminalVerdict(promptText, hasInputField, s.opType)
        if (verdict != null) {
            UssdLog.add(if (verdict) "🏁 Verdict opérateur : SUCCÈS" else "🛑 Verdict opérateur : ÉCHEC")
            feed(null, false, true)
            finish(s, UssdResult(s.operationId, verdict, s.transcript.toString(),
                if (verdict) null else promptText.take(200)))
            return
        }

        if (hasInputField && s.inputs.isNotEmpty()) {
            val next = s.inputs.removeFirst()
            UssdLog.add("➡️ Saisie: '$next' (reste ${s.inputs.size})")
            feed(next, true, false)
        } else {
            val success = if (s.opType == "DEPOT") isSuccessMessage(promptText) else !isExplicitFailure(promptText)
            UssdLog.add("🏁 Fin de séquence (succès=$success)")
            feed(null, false, true)
            finish(s, UssdResult(s.operationId, success, s.transcript.toString()))
        }
    }

    /** Normalisation (minuscules, sans accents) pour l'analyse des messages. */
    private fun nrm(t: String): String =
        java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").replace(Regex("\\s+"), " ").trim()

    /**
     * Le message est-il un SUCCÈS reconnu ? (formats relevés sur le terrain)
     *   ORANGE dépôt     : « Dépot effectué Vous allez recevoir une confirmation par SMS »
     *   ORANGE transfert : « Transfert d'argent effectué avec succès vers le X »
     *   MTN dépôt        : « Votre Depot de X FCFA a ete effectue avec succes sur le compte du Y »
     *   MOOV dépôt       : « Vous avez envoye X FCFA vers le Y … »
     */
    fun isSuccessMessage(text: String): Boolean {
        val t = nrm(text)
        if (t.isEmpty()) return false
        if (Regex("transfert d'?argent effectue avec succes").containsMatchIn(t)) return true
        if (Regex("depot effectue").containsMatchIn(t)) return true
        if (Regex("depot de .{0,40}a ete effectue avec succes").containsMatchIn(t)) return true
        if (Regex("vous avez envoye .{0,40}vers le").containsMatchIn(t)) return true
        if (Regex("(effectue|effectuee|envoye|transfere) avec succes").containsMatchIn(t)) return true
        return false
    }

    /**
     * Verdict FINAL de l'opérateur.
     *
     * Règle : sur un écran TERMINAL (plus aucun champ de saisie, l'opérateur a
     * rendu sa réponse), le résultat est binaire — SUCCÈS si le message fait
     * partie des formats de réussite connus, ÉCHEC dans TOUS les autres cas.
     * C'est volontairement une liste blanche : les formulations d'échec sont
     * innombrables (« initiate not fund », plafonds, solde insuffisant, codes
     * d'erreur…) et une liste noire en oublierait toujours.
     *
     * Tant qu'un champ de saisie est présent, il s'agit d'un menu ou d'une invite :
     * jamais un verdict, la séquence se poursuit.
     */
    fun terminalVerdict(text: String, hasInputField: Boolean, opType: String): Boolean? {
        if (hasInputField) return null          // menu / invite -> on continue
        val t = nrm(text)
        if (t.isEmpty()) return null
        // RETRAIT : l'USSD ne fait qu'INITIER la demande (l'abonné doit approuver).
        // On ne conclut donc PAS a l'echec sur liste blanche : seul un message
        // d'echec EXPLICITE echoue ; sinon la demande est partie et c'est le SMS
        // recu apres coup qui validera (ancienne logique, cote serveur).
        if (opType != "DEPOT") {
            if (isExplicitFailure(t)) return false
            return true                          // demande initiee -> pas d'echec, on laisse le SMS confirmer
        }
        return isSuccessMessage(t)              // DEPOT : succes connu, sinon echec
    }

    /** Echec USSD explicite (sert aux RETRAITS uniquement). */
    private fun isExplicitFailure(t: String): Boolean {
        return Regex("(solde insuffisant|insuffisant|echec|echoue|refuse|incorrect|invalide|impossible|non abouti|non disponible|indisponible|initiate not fund|not fund|montant maximum cumule|limite maximum|plafond)").containsMatchIn(nrm(t))
    }

    private fun finish(s: Session, result: UssdResult) {
        if (s.finished) return
        s.finished = true
        if (session === s) { session = null; dialStartedAt = 0L; desiredSimSlot = -1; simPickerDone = false; desiredSimNames = emptyList() }
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

    /** Attache le bon PhoneAccountHandle correspondant au slot SIM PHYSIQUE. */
    private fun attachSimAccount(ctx: Context, intent: Intent, simSlot: Int) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            val handle = phoneAccountForSlot(ctx, simSlot)
            if (handle != null) {
                intent.putExtra(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                UssdLog.add("📶 SIM slot $simSlot → compte '${handle.id}'")
            } else {
                UssdLog.add("📶 SIM slot $simSlot : compte non résolu — le sélecteur SIM sera géré par l'accessibilité")
            }
        } catch (_: SecurityException) {} catch (_: Exception) {}
    }

    /**
     * Mappe un slot SIM PHYSIQUE → PhoneAccountHandle.
     * On NE se fie PLUS à l'ordre de `callCapablePhoneAccounts` (faux sur beaucoup
     * d'appareils, dont Transsion) : on passe par le subscriptionId du slot.
     */
    private fun phoneAccountForSlot(ctx: Context, slot: Int): android.telecom.PhoneAccountHandle? {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return null
            val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
            @Suppress("MissingPermission")
            val info = sm.getActiveSubscriptionInfoForSimSlotIndex(slot)
            if (info == null) { UssdLog.add("⚠️ Aucune SIM active au slot $slot"); return null }
            val subId = info.subscriptionId
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            @Suppress("MissingPermission")
            val handles = tm.callCapablePhoneAccounts ?: return null
            if (handles.isEmpty()) return null

            // 1) API 30+ : correspondance fiable subscriptionId ↔ handle.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val telMgr = ctx.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                for (h in handles) {
                    try {
                        @Suppress("MissingPermission")
                        if (telMgr.getSubscriptionId(h) == subId) return h
                    } catch (_: Exception) {}
                }
            }
            // 2) Correspondance par identifiant de handle (== subId, ou contient l'ICCID).
            val subIdStr = subId.toString()
            val iccId = try { info.iccId } catch (_: Exception) { null }
            for (h in handles) {
                val id = h.id ?: continue
                if (id == subIdStr) return h
                if (!iccId.isNullOrEmpty() && (id == iccId || id.contains(iccId))) return h
            }
            // 3) Dernier recours : index positionnel (le sélecteur d'accessibilité corrigera au besoin).
            return handles.getOrNull(slot)
        } catch (_: SecurityException) { return null } catch (_: Exception) { return null }
    }

    /** Libellés (nom d'affichage + opérateur) de la SIM du slot, utilisés par le sélecteur. */
    private fun simNamesForSlot(ctx: Context, slot: Int): List<String> {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return emptyList()
            val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
            @Suppress("MissingPermission")
            val info = sm.getActiveSubscriptionInfoForSimSlotIndex(slot) ?: return emptyList()
            listOfNotNull(info.displayName?.toString(), info.carrierName?.toString())
                .map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        } catch (_: Exception) { emptyList() }
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

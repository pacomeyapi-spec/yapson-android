package net.yapson.mobile.service

import net.yapson.mobile.model.Operation
import net.yapson.mobile.utils.UssdLog

data class AppResult(val success: Boolean, val message: String?, val ref: String?)

/**
 * Moteur canal APP : exécute un transfert dans Wave Business / Orange Max it via
 * le service d'accessibilité (primitives appXxx de UssdAccessibilityService).
 * Flux validés en Phase 1. Le code à saisir vient de op.code (pin SIM, serveur).
 */
object AppRunner {

    private const val WAVE_PKG = "com.wave.business"
    private const val ORANGE_PKG = "com.orange.myorange.oci"

    fun run(op: Operation): AppResult {
        val s = UssdAccessibilityService.instance ?: return fail("service d'accessibilité inactif")
        return try {
            when (op.operator.uppercase()) {
                "WAVE" -> wave(s, op)
                "ORANGE" -> orange(s, op)
                else -> fail("opérateur APP non supporté: ${op.operator}")
            }
        } catch (e: Exception) { fail("exception: ${e.message}") }
    }

    private fun fail(m: String): AppResult { UssdLog.add("❌ APP: $m"); return AppResult(false, m, null) }
    private fun amountStr(op: Operation): String = op.amount.toLong().toString()

    // ───────────────────────── WAVE (code au début) ─────────────────────────
    private fun wave(s: UssdAccessibilityService, op: Operation): AppResult {
        val amt = amountStr(op); val num = op.phoneNumber; val name = op.recipientName ?: ""; val code = op.code ?: ""
        UssdLog.add("=== APP WAVE $amt -> $name/$num ===")
        if (!s.appLaunch(WAVE_PKG)) return fail("lancement Wave impossible")
        Thread.sleep(2500)
        if (!s.appReachHome("ENCAISSER UN CLIENT")) return fail("accueil Wave non atteint")
        if (!s.appClickText("Transfert", true)) return fail("action Transfert non cliquée")
        if (s.appWaitForText("Transférer à d'autres", 10000) == null) return fail("feuille type non détectée")
        s.appClickText("Transférer à d'autres")
        Thread.sleep(1300)
        if (s.findByText("Envoyer de l'Argent") == null) {
            if (code.isNotEmpty()) { UssdLog.add("🔑 code Wave (auto)"); s.appTapDigits(code) }
            else UssdLog.add("⏸️ code Wave absent — saisie manuelle attendue")
            if (s.appWaitForText("Envoyer de l'Argent", 90000) == null) return fail("écran d'envoi non atteint (code ?)")
        }
        Thread.sleep(600)
        if (!s.appFocusAndSet(num)) return fail("champ numéro introuvable")
        Thread.sleep(500)
        if (!s.appClickText("Saisir un nouveau numéro")) return fail("'Saisir un nouveau numéro' introuvable")
        if (s.appWaitForText("Nom complet", 10000) == null) return fail("écran nom non atteint")
        Thread.sleep(500)
        if (!s.appFocusAndSet(name)) return fail("champ nom introuvable")
        if (!s.appClickText("Suivant")) return fail("bouton Suivant introuvable")
        if (s.appWaitForText("Montant", 12000) == null) return fail("écran montant non atteint")
        Thread.sleep(600)
        s.appEnterAmount(amt)
        if (!s.appClickWhenReady("Envoyer", true)) return fail("bouton Envoyer non actif (montant non pris en compte)")
        if (s.appWaitForText("Confirmer la Transaction", 12000) == null) return fail("récap non atteint")
        s.appClickText("Confirmer", true)
        return if (s.appWaitForText("ENCAISSER UN CLIENT", 30000) != null)
            AppResult(true, "transfert Wave confirmé", null)
        else fail("confirmation non vérifiée (retour accueil non détecté)")
    }

    // ───────────────────────── ORANGE (code à la fin, clavier mélangé) ─────────────────────────
    private fun orange(s: UssdAccessibilityService, op: Operation): AppResult {
        val amt = amountStr(op); val num = op.phoneNumber; val code = op.code ?: ""
        UssdLog.add("=== APP ORANGE $amt -> $num ===")
        if (!s.appLaunch(ORANGE_PKG)) return fail("lancement Max it impossible")
        Thread.sleep(2500)
        if (!s.appReachHome("Mes favoris")) return fail("accueil Max it non atteint")
        if (!s.appClickText("Transfert d'argent")) return fail("favori Transfert d'argent non cliqué")
        if (s.appWaitForText("Contacts", 15000) == null) return fail("écran numéro non détecté")
        Thread.sleep(700)
        if (!s.appFocusAndSet(num)) return fail("champ numéro introuvable")
        if (s.appWaitForText("Confirmer numéro", 8000) == null) return fail("bouton Confirmer numéro absent")
        s.appClickText("Confirmer numéro")
        if (s.appWaitForText("Montant à transférer", 15000) == null) return fail("écran montant non atteint")
        Thread.sleep(700)
        s.appEnterAmount(amt)
        if (!s.appClickWhenReady("Transférer", true)) return fail("bouton Transférer non actif (montant non pris en compte)")
        if (s.appWaitForText("Récap de transaction", 15000) == null) return fail("récap non atteint")
        s.appClickText("Confirmer", true)
        if (s.appWaitForText("Saisissez votre code secret", 15000) == null) return fail("écran code non atteint")
        if (code.isNotEmpty()) {
            UssdLog.add("🔑 code Orange (auto, clavier mélangé)")
            if (!s.appTapDigits(code)) return fail("saisie code échouée")
            s.appClickText("Confirmer", true)
        } else UssdLog.add("⏸️ code Orange absent — saisie manuelle attendue")
        if (s.appWaitForText("Montant transféré", 90000) == null && s.findByText("Confirmation") == null)
            return fail("confirmation non vérifiée")
        val ref = s.appAllTexts().firstOrNull { it.startsWith("PP") && it.length >= 8 }
        UssdLog.add("🧾 réf: ${ref ?: "(non lue)"}")
        return AppResult(true, "transfert Orange confirmé", ref)
    }
}

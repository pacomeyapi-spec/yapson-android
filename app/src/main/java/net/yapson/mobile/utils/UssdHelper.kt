package net.yapson.mobile.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.delay
import net.yapson.mobile.service.UssdAccessibilityService

object UssdHelper {

    private const val TAG = "UssdHelper"

    /**
     * Exécute une séquence USSD multi-étapes via AccessibilityService.
     *
     * Fonctionnement :
     * - Étape 1 : lancer le code USSD via Intent ACTION_CALL (ouvre le menu)
     * - Étapes suivantes : le UssdAccessibilityService détecte la fenêtre USSD
     *   et saisit automatiquement chaque réponse + clique sur Envoyer
     */
    suspend fun executeSteps(
        ctx: Context,
        steps: List<String>,
        onStep: (Int, String) -> Unit = { _, _ -> },
        onDone: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (steps.isEmpty()) {
            onError("Aucune étape USSD définie")
            return
        }

        val firstStep = steps[0]
        Log.d(TAG, "Étape 1/${steps.size}: $firstStep")
        onStep(1, firstStep)

        // Étape 1 : lancer le code USSD initial via Intent
        try {
            val encoded = firstStep
                .replace("#", Uri.encode("#"))
                .replace("*", Uri.encode("*"))
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$encoded")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur étape 1: ${e.message}")
            onError("Erreur lancement USSD: ${e.message}")
            return
        }

        if (steps.size == 1) {
            delay(3000)
            onDone()
            return
        }

        // Démarrer le service d'accessibilité pour les étapes restantes
        UssdAccessibilityService.startSequence(
            steps = steps,
            onStep = { num, code -> onStep(num, code) },
            onDone = {
                Log.d(TAG, "✅ Séquence USSD complète via AccessibilityService")
                onDone()
            },
            onErr = { err ->
                Log.e(TAG, "Erreur AccessibilityService: $err")
                onError(err)
            }
        )

        // Attendre que la séquence se termine (max 60 secondes)
        var waited = 0
        while (UssdAccessibilityService.isActive && waited < 60000) {
            delay(500)
            waited += 500
        }

        if (waited >= 60000) {
            onError("Timeout séquence USSD")
            UssdAccessibilityService.reset()
        }
    }

    fun dial(ctx: Context, ussdCode: String) {
        try {
            val encoded = ussdCode
                .replace("#", Uri.encode("#"))
                .replace("*", Uri.encode("*"))
            ctx.startActivity(Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$encoded")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) {
            Log.e(TAG, "dial error: ${e.message}")
        }
    }
}

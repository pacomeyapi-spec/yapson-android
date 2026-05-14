package net.yapson.mobile.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object UssdHelper {

    private const val TAG = "UssdHelper"

    /**
     * Exécute une séquence USSD complète via TelecomManager.sendUssdRequest.
     *
     * Étape 1  : lancée via Intent ACTION_CALL (ouvre le menu USSD)
     * Étapes 2+: envoyées via TelephonyManager.sendUssdRequest() qui répond
     *            aux menus interactifs — API officielle Android 8+, fonctionne
     *            sur Samsung, Pixel, et tous les autres.
     */
    suspend fun executeSteps(
        ctx: Context,
        steps: List<String>,
        onStep: (Int, String) -> Unit = { _, _ -> },
        onDone: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (steps.isEmpty()) {
            onError("Aucune étape USSD")
            return
        }

        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm == null) {
            onError("TelephonyManager non disponible")
            return
        }

        // ── Étape 1 : lancer le code USSD initial ────────────────────────
        val firstStep = steps[0]
        Log.d(TAG, "▶ Étape 1/${steps.size}: $firstStep")
        onStep(1, firstStep)

        // Encoder le code USSD pour l'Intent
        val encoded = firstStep
            .replace("#", Uri.encode("#"))
            .replace("*", Uri.encode("*"))
        try {
            ctx.startActivity(Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$encoded")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) {
            onError("Erreur lancement USSD: ${e.message}")
            return
        }

        if (steps.size == 1) {
            delay(3000)
            onDone()
            return
        }

        // ── Étapes suivantes : sendUssdRequest ───────────────────────────
        // Attendre que le menu USSD initial s'affiche
        delay(5000)

        for (i in 1 until steps.size) {
            val step = steps[i]
            val stepNum = i + 1
            Log.d(TAG, "▶ Étape $stepNum/${steps.size}: $step")
            onStep(stepNum, step)

            val success = sendUssdStep(tm, step)
            if (!success) {
                Log.w(TAG, "sendUssdRequest échoué pour étape $stepNum — on continue")
            }

            // Délai adaptatif entre étapes
            val waitMs = when {
                step.length > 5 -> 4000L  // numéro ou montant
                else -> 3000L             // chiffre simple
            }
            delay(waitMs)
        }

        Log.d(TAG, "✅ Séquence USSD terminée")
        onDone()
    }

    /**
     * Envoie une réponse USSD via TelephonyManager.sendUssdRequest.
     * Utilise le callback système — fonctionne sur Samsung, Pixel, etc.
     */
    private suspend fun sendUssdStep(
        tm: TelephonyManager,
        ussdCode: String
    ): Boolean = suspendCancellableCoroutine { cont ->
        try {
            tm.sendUssdRequest(
                ussdCode,
                object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(
                        telephonyManager: TelephonyManager,
                        request: String,
                        response: CharSequence
                    ) {
                        Log.d(TAG, "Réponse USSD pour '$request': $response")
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onReceiveUssdResponseFailed(
                        telephonyManager: TelephonyManager,
                        request: String,
                        failureCode: Int
                    ) {
                        // Code -1 = menu interactif en cours (normal pour les étapes intermédiaires)
                        // Code 0  = pas de réseau
                        Log.d(TAG, "USSD '$request' failCode=$failureCode (normal pour étapes intermédiaires)")
                        if (cont.isActive) cont.resume(failureCode == -1)
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission CALL_PHONE manquante: ${e.message}")
            if (cont.isActive) cont.resume(false)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur sendUssdRequest: ${e.message}")
            if (cont.isActive) cont.resume(false)
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

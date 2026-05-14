package net.yapson.mobile.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.delay

object UssdHelper {

    private const val TAG = "UssdHelper"

    /**
     * Exécute une séquence USSD multi-étapes.
     * - Étape 1 : code initial (*145#) lancé via Intent ACTION_CALL
     * - Étapes suivantes : réponses aux menus via sendUssdRequest (Android 8+)
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

        // Étape 1 : lancer le code USSD initial
        val firstStep = steps[0]
        Log.d(TAG, "Étape 1/${steps.size}: $firstStep")
        onStep(1, firstStep)

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
            onError("Erreur étape 1: ${e.message}")
            return
        }

        val remainingSteps = steps.drop(1)
        if (remainingSteps.isEmpty()) {
            delay(3000)
            onDone()
            return
        }

        // Attendre que le menu USSD s'affiche
        delay(4000)

        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm == null) {
            onError("TelephonyManager non disponible")
            return
        }

        // Étapes suivantes
        for ((index, step) in remainingSteps.withIndex()) {
            val stepNum = index + 2
            Log.d(TAG, "Étape $stepNum/${steps.size}: $step")
            onStep(stepNum, step)

            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    tm.sendUssdRequest(
                        step,
                        object : TelephonyManager.UssdResponseCallback() {
                            override fun onReceiveUssdResponse(
                                telephonyManager: TelephonyManager,
                                request: String,
                                response: CharSequence
                            ) {
                                Log.d(TAG, "Réponse USSD étape $stepNum: $response")
                            }
                            override fun onReceiveUssdResponseFailed(
                                telephonyManager: TelephonyManager,
                                request: String,
                                failureCode: Int
                            ) {
                                // Normal pour les étapes intermédiaires
                                Log.w(TAG, "USSD étape $stepNum code=$failureCode (peut être normal)")
                            }
                        },
                        Handler(Looper.getMainLooper())
                    )
                } else {
                    // Fallback Android < 8
                    dialSingle(ctx, step)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission manquante étape $stepNum: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Erreur étape $stepNum: ${e.message}")
            }

            // Délai adaptatif entre étapes
            val delayMs = if (step.length > 5) 3000L else 2000L
            delay(delayMs)
        }

        Log.d(TAG, "✅ Séquence USSD terminée (${steps.size} étapes)")
        onDone()
    }

    private fun dialSingle(ctx: Context, code: String) {
        try {
            val encoded = code.replace("#", Uri.encode("#")).replace("*", Uri.encode("*"))
            ctx.startActivity(Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$encoded")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) {
            Log.e(TAG, "dialSingle error: ${e.message}")
        }
    }

    fun dial(ctx: Context, ussdCode: String) {
        dialSingle(ctx, ussdCode)
    }
}

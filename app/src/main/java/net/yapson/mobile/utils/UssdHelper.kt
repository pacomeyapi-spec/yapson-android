package net.yapson.mobile.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log

object UssdHelper {

    private const val TAG = "UssdHelper"

    /**
     * Lance un code USSD via l'Intent téléphone.
     * Encode le code pour URI (# → %23)
     */
    fun dial(ctx: Context, ussdCode: String) {
        try {
            // Encoder le code USSD pour URI
            val encoded = ussdCode
                .replace("#", Uri.encode("#"))
                .replace("*", Uri.encode("*"))

            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$encoded")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
            Log.d(TAG, "USSD lancé: $ussdCode")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur USSD dial: ${e.message}")
        }
    }

    /**
     * Construit le code USSD final en remplaçant les placeholders.
     * Template exemple: *144*1*{numero}*{montant}#
     */
    fun buildCode(template: String, phoneNumber: String, amount: Double): String {
        val amountStr = amount.toLong().toString() // pas de décimales pour USSD
        return template
            .replace("{numero}", phoneNumber.replace(" ", ""))
            .replace("{montant}", amountStr)
            .replace("{pin}", "") // PIN vide par défaut
    }
}

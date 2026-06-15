package net.yapson.mobile.utils

import android.content.Context
import android.os.Build
import android.provider.Telephony

/** Lecture de la boîte SMS pour récupérer le solde du dernier message +454. */
object SmsReader {
    data class Sms(val body: String, val ts: Long)

    /**
     * Dernier SMS reçu d'un expéditeur dont l'adresse contient [senderContains] (ex "454").
     * Si [subId] >= 0, on ne lit QUE les SMS reçus sur cette SIM (subscriptionId) — indispensable
     * quand l'appareil a deux SIM du même opérateur (deux comptes, deux soldes distincts).
     */
    fun lastFrom(ctx: Context, senderContains: String, subId: Int = -1): Sms? {
        return try {
            val cols = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
            val sel: String?; val args: Array<String>?
            if (subId >= 0) { sel = Telephony.Sms.SUBSCRIPTION_ID + "=?"; args = arrayOf(subId.toString()) }
            else { sel = null; args = null }
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, cols, sel, args, Telephony.Sms.DATE + " DESC"
            )?.use { c ->
                val iA = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val iB = c.getColumnIndex(Telephony.Sms.BODY)
                val iD = c.getColumnIndex(Telephony.Sms.DATE)
                while (c.moveToNext()) {
                    val addr = c.getString(iA) ?: ""
                    if (addr.contains(senderContains)) return Sms(c.getString(iB) ?: "", c.getLong(iD))
                }
            }
            null
        } catch (e: Exception) { null }
    }

    /** subscriptionId de la SIM au slot physique [slot] (-1 si indisponible). */
    fun subIdForSlot(ctx: Context, slot: Int): Int {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return -1
            val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager
            @Suppress("MissingPermission")
            val info = sm.getActiveSubscriptionInfoForSimSlotIndex(slot) ?: return -1
            info.subscriptionId
        } catch (e: Exception) { -1 }
    }

    /**
     * Extrait le solde annoncé après "Solde:" (ex "Solde:181812.00 FCFA" -> 181812).
     * On ancre STRICTEMENT sur "Solde" pour ne jamais prendre le montant transféré ni les frais.
     */
    fun parseSolde(body: String): Long? {
        val m = Regex("(?i)solde\\s*:?\\s*([0-9][0-9 .,\\u00A0]*)").find(body) ?: return null
        var raw = m.groupValues[1].trim()
        raw = raw.replace(Regex("[.,]\\d{2}(?!\\d)"), "")  // enlève les décimales .00 / ,00
        raw = raw.replace(Regex("[ .,\\u00A0]"), "")        // enlève séparateurs de milliers / espaces
        return raw.toLongOrNull()
    }

    /** Vrai si le SMS annonce un transfert réussi. */
    fun isSuccess(body: String): Boolean {
        val b = body.lowercase()
        return b.contains("succes") || b.contains("succès") || b.contains("reussi") || b.contains("réussi")
    }
}

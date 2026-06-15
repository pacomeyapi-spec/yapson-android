package net.yapson.mobile.utils

import android.content.Context
import android.provider.Telephony

/** Lecture de la boîte SMS pour récupérer le solde du dernier message +454. */
object SmsReader {
    data class Sms(val body: String, val ts: Long)

    /** Dernier SMS reçu d'un expéditeur dont l'adresse contient [senderContains] (ex "454"). */
    fun lastFrom(ctx: Context, senderContains: String): Sms? {
        return try {
            val cols = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, cols, null, null, Telephony.Sms.DATE + " DESC"
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

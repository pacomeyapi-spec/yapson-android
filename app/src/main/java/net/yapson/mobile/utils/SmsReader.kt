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

    // Cache du subId réellement exploitable (évite de re-sonder la boîte SMS à chaque cycle).
    private var effCache: Pair<Int, Int>? = null   // (subIdDemandé -> subIdUtilisable)
    /** Vrai s'il existe au moins un SMS dans la boîte avec ce SUBSCRIPTION_ID. */
    private fun hasRowsForSub(ctx: Context, subId: Int): Boolean {
        return try {
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.DATE),
                Telephony.Sms.SUBSCRIPTION_ID + "=?", arrayOf(subId.toString()), null
            )?.use { it.moveToFirst() } ?: false
        } catch (e: Exception) { false }
    }
    /**
     * subId réellement utilisable pour filtrer la boîte SMS.
     *
     * Certains constructeurs (Huawei/EMUI en particulier) ne renseignent PAS la
     * colonne SUBSCRIPTION_ID comme Android standard : ils y mettent -1, ou l'index
     * du slot. Le filtre `SUBSCRIPTION_ID=<subId>` ne renvoyait alors AUCUNE ligne
     * alors que les SMS sont bien présents -> l'auto-dépôt concluait « pas de SMS
     * +454 de confirmation » et relançait une sonde de 200 F à chaque cycle.
     *
     * On vérifie donc que le filtre rend au moins une ligne ; sinon on essaie
     * l'index du slot ; sinon on lit SANS filtre (-1).
     */
    fun effectiveSubId(ctx: Context, slot: Int, subId: Int): Int {
        effCache?.let { if (it.first == subId) return it.second }
        val res = when {
            subId >= 0 && hasRowsForSub(ctx, subId) -> subId
            slot >= 0 && hasRowsForSub(ctx, slot)   -> slot     // EMUI : slot stocké à la place du subId
            else                                    -> -1        // colonne inexploitable : lecture non filtrée
        }
        effCache = Pair(subId, res)
        return res
    }
    /** Force la re-détection (changement de SIM, etc.). */
    fun resetSubIdCache() { effCache = null }

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

    /**
     * Tous les SMS d'un expéditeur reçus APRÈS [sinceTs], du plus ancien au plus récent.
     * lastFrom() ne rend que le tout dernier : insuffisant pendant un dépôt, car un
     * paiement client peut arriver juste après la confirmation et la masquer.
     */
    fun listFrom(ctx: Context, senderContains: String, subId: Int = -1, sinceTs: Long = 0L): List<Sms> {
        val out = ArrayList<Sms>()
        try {
            val cols = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
            val sel: String?; val args: Array<String>?
            if (subId >= 0) { sel = Telephony.Sms.SUBSCRIPTION_ID + "=?"; args = arrayOf(subId.toString()) }
            else { sel = null; args = null }
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, cols, sel, args, Telephony.Sms.DATE + " ASC"
            )?.use { c ->
                val iA = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val iB = c.getColumnIndex(Telephony.Sms.BODY)
                val iD = c.getColumnIndex(Telephony.Sms.DATE)
                while (c.moveToNext()) {
                    val addr = c.getString(iA) ?: ""
                    val ts = c.getLong(iD)
                    if (addr.contains(senderContains) && ts > sinceTs) out.add(Sms(c.getString(iB) ?: "", ts))
                }
            }
        } catch (e: Exception) { /* boite SMS illisible : on rend ce qu'on a */ }
        return out
    }

    /**
     * Vrai si le SMS annonce un transfert REÇU d'un tiers — autrement dit un paiement
     * client ("Transfert de 500.00F recu du 07XXXXXXXX"), et non l'issue d'une de nos
     * opérations. Ces notifications tombent à n'importe quel moment, y compris pendant
     * qu'un dépôt s'exécute : sans ce filtre elles étaient prises pour la confirmation
     * du dépôt, ne contenaient évidemment aucun marqueur de succès, et déclenchaient
     * une fausse alerte ECHEC.
     *
     * Volontairement ÉTROIT : on n'écarte que ce qu'on identifie avec certitude. Tout
     * SMS non reconnu continue de suivre le chemin normal, pour ne jamais masquer un
     * vrai échec.
     */
    fun isIncomingTransfer(body: String): Boolean {
        val b = body.lowercase()
        return Regex("re[çc]u\\s+d[eu]\\b").containsMatchIn(b)
    }

    /**
     * Diagnostic : combien de SMS de [senderContains] sont visibles selon le mode de
     * lecture. Permet de savoir si c'est le filtre SIM (SUBSCRIPTION_ID) qui masque
     * les SMS (cas Huawei/EMUI) ou si la boîte est réellement vide/inaccessible.
     */
    fun diagnostic(ctx: Context, senderContains: String, subId: Int, slot: Int): String {
        fun cnt(sel: String?, args: Array<String>?): Int {
            return try {
                var n = 0
                ctx.contentResolver.query(
                    Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS), sel, args, null
                )?.use { c ->
                    val iA = c.getColumnIndex(Telephony.Sms.ADDRESS)
                    while (c.moveToNext()) if ((c.getString(iA) ?: "").contains(senderContains)) n++
                }
                n
            } catch (e: Exception) { -1 }
        }
        val sansFiltre = cnt(null, null)
        val avecSub = if (subId >= 0) cnt(Telephony.Sms.SUBSCRIPTION_ID + "=?", arrayOf(subId.toString())) else -2
        val avecSlot = if (slot >= 0) cnt(Telephony.Sms.SUBSCRIPTION_ID + "=?", arrayOf(slot.toString())) else -2
        return "SMS $senderContains visibles → sans filtre: $sansFiltre | subId=$subId: $avecSub | slot=$slot: $avecSlot"
    }

    /** Vrai si le SMS annonce un transfert réussi. */
    fun isSuccess(body: String): Boolean {
        val b = body.lowercase()
        return b.contains("succes") || b.contains("succès") || b.contains("reussi") || b.contains("réussi")
    }
}

package net.yapson.mobile.service

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import androidx.annotation.RequiresApi
import net.yapson.mobile.utils.Prefs

/**
 * Rejette automatiquement les appels entrants pour qu'aucun appel ne perturbe les
 * transactions. Actif uniquement si l'app détient le rôle "filtrage d'appels"
 * (à accorder depuis l'écran principal) et si Prefs.rejectCalls est vrai.
 */
@RequiresApi(Build.VERSION_CODES.N)
class CallRejectService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        // callDirection n'est lisible qu'à partir d'Android 10 (Q). Avant, le
        // filtrage ne concerne de toute façon que les appels entrants.
        val incoming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            callDetails.callDirection == Call.Details.DIRECTION_INCOMING
        else true

        val response = CallResponse.Builder()
        if (Prefs.rejectCalls && incoming) {
            response.setDisallowCall(true)   // ne pas présenter l'appel
                .setRejectCall(true)         // raccrocher
                .setSkipNotification(true)   // pas de notification d'appel manqué
            Log.d("CallReject", "Appel entrant rejeté")
        }
        respondToCall(callDetails, response.build())
    }
}

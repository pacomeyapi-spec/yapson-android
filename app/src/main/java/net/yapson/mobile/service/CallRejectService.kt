package net.yapson.mobile.service

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.annotation.RequiresApi

/**
 * Rejette systématiquement tout appel ENTRANT pour ne pas perturber l'auto-dépôt.
 * Nécessite que l'app détienne le rôle ROLE_CALL_SCREENING (demandé dans MainActivity).
 */
@RequiresApi(Build.VERSION_CODES.Q)
class CallRejectService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val incoming = callDetails.callDirection == Call.Details.DIRECTION_INCOMING
        if (!incoming) return
        val resp = CallResponse.Builder()
            .setDisallowCall(true)
            .setRejectCall(true)
            .setSkipCallLog(false)
            .setSkipNotification(true)
            .build()
        respondToCall(callDetails, resp)
    }
}

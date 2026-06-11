package net.yapson.mobile.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.yapson.mobile.api.ApiClient
import net.yapson.mobile.utils.Prefs

class SmsReceiver : BroadcastReceiver() {

    private val TAG = "SmsReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Regrouper les parties d'un même SMS
        val grouped = messages.groupBy { it.originatingAddress }

        grouped.forEach { (sender, parts) ->
            val fullMessage = parts.joinToString("") { it.messageBody }
            val senderStr = sender ?: "unknown"

            Log.d(TAG, "SMS reçu de $senderStr: ${fullMessage.take(80)}")

            if (!Prefs.isConfigured()) return@forEach

            // Envoyer au backend
            CoroutineScope(Dispatchers.IO).launch {
                val opId = Prefs.attributableOperationId()
                val success = ApiClient.sendSms(opId, fullMessage, senderStr)
                Log.d(TAG, "SMS envoyé backend: $success")
            }
        }
    }
}

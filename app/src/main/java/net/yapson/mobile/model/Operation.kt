package net.yapson.mobile.model

import com.google.gson.annotations.SerializedName

data class Operation(
    val id: String = "",
    val reference: String = "",
    val type: String = "",       // DEPOT ou RETRAIT
    val productKind: String? = null,  // "VENTE"/"SOUSCRIPTION" (airtime) ou null
    val status: String = "",
    val operator: String = "",   // ORANGE, MTN, MOOV, WAVE
    val amount: Double = 0.0,
    val phoneNumber: String = "",
    val ussdCode: String? = null,
    // Étapes USSD multi-étapes (nouveau format)
    // Liste ordonnée ex: ["*145#", "1", "0788334833", "13200", "1234", "1"]
    val ussdSteps: List<String>? = null,
    // Slot SIM choisi côté plateforme (0 = SIM1, 1 = SIM2)
    val simSlot: Int = 0,
    // Canal d'exécution : "USSD" (défaut) ou "APP" (Wave Business / Orange Max it)
    val channel: String = "USSD",
    // Nom du destinataire (canal APP, obligatoire pour Wave)
    val recipientName: String? = null,
    // Code à saisir (canal APP) — fourni par le serveur depuis le pin SIM
    val code: String? = null,
    @SerializedName("operatorConfig")
    val operatorConfig: OperatorConfig? = null
)

data class OperatorConfig(
    val operator: String = "",
    val name: String = "",
    val ussdDepot: String? = null,
    val ussdRetrait: String? = null,
    val timeoutSeconds: Int = 120,
    val usesNotification: Boolean = false,
    val notifPackageName: String? = null
)

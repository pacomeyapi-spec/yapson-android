package net.yapson.mobile.model

import com.google.gson.annotations.SerializedName

data class Operation(
    val id: String = "",
    val reference: String = "",
    val type: String = "",       // DEPOT ou RETRAIT
    val status: String = "",
    val operator: String = "",   // ORANGE, MTN, MOOV, WAVE
    val amount: Double = 0.0,
    val phoneNumber: String = "",
    val ussdCode: String? = null,
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

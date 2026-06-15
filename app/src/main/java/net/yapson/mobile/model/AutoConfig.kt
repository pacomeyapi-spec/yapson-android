package net.yapson.mobile.model

/** Réglages d'auto-dépôt renvoyés par GET /api/android/auto-config. */
data class AutoConfig(
    val enabled: Boolean = false,
    val simSlot: Int = 0,
    val destination: String = "",
    val intervalSec: Int = 60,
    val minAmount: Int = 10,
    val maxAmount: Int = 0,
    val probeAmount: Int = 200,
    val ntfyTopic: String = "",
    val smsSender: String = "+454"
)

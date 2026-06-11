package net.yapson.mobile.utils

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val PREFS_NAME = "yapson_config"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Backend URL
    var backendUrl: String
        get() = _ctx?.let { prefs(it).getString("backend_url", "") } ?: ""
        set(v) { _ctx?.let { prefs(it).edit().putString("backend_url", v).apply() } }

    // Device token (fourni par l'admin backend)
    var deviceToken: String
        get() = _ctx?.let { prefs(it).getString("device_token", "") } ?: ""
        set(v) { _ctx?.let { prefs(it).edit().putString("device_token", v).apply() } }

    // Device ID
    var deviceId: String
        get() = _ctx?.let { prefs(it).getString("device_id", "") } ?: ""
        set(v) { _ctx?.let { prefs(it).edit().putString("device_id", v).apply() } }

    // Packages à surveiller pour Wave (séparés par virgule)
    var wavePackages: String
        get() = _ctx?.let { prefs(it).getString("wave_packages", "com.wave.finance,com.wave.senegal") } ?: "com.wave.finance,com.wave.senegal"
        set(v) { _ctx?.let { prefs(it).edit().putString("wave_packages", v).apply() } }

    // Service actif
    var serviceEnabled: Boolean
        get() = _ctx?.let { prefs(it).getBoolean("service_enabled", false) } ?: false
        set(v) { _ctx?.let { prefs(it).edit().putBoolean("service_enabled", v).apply() } }

    // Intervalle polling (secondes)
    var pollInterval: Int
        get() = _ctx?.let { prefs(it).getInt("poll_interval", 5) } ?: 5
        set(v) { _ctx?.let { prefs(it).edit().putInt("poll_interval", v).apply() } }

    // Opération en cours (id)
    var currentOperationId: String
        get() = _ctx?.let { prefs(it).getString("current_op_id", "") } ?: ""
        set(v) { _ctx?.let { prefs(it).edit().putString("current_op_id", v).apply() } }

    // Dernière opération terminée (pour rattacher SMS/notif de confirmation qui
    // arrivent quelques secondes APRÈS la fin du transfert).
    var lastOperationId: String
        get() = _ctx?.let { prefs(it).getString("last_op_id", "") } ?: ""
        set(v) { _ctx?.let { prefs(it).edit().putString("last_op_id", v).apply() } }

    var lastOperationAt: Long
        get() = _ctx?.let { prefs(it).getLong("last_op_at", 0L) } ?: 0L
        set(v) { _ctx?.let { prefs(it).edit().putLong("last_op_at", v).apply() } }

    // Fenêtre de grâce (ms) pendant laquelle un SMS/notif est rattaché à la dernière opération.
    const val GRACE_WINDOW_MS: Long = 180_000L

    /** Id d'opération auquel rattacher un SMS/notif entrant : l'opération en cours,
     *  sinon la dernière terminée si elle est encore dans la fenêtre de grâce. */
    fun attributableOperationId(): String? {
        val cur = currentOperationId
        if (cur.isNotBlank()) return cur
        val last = lastOperationId
        if (last.isNotBlank() && (System.currentTimeMillis() - lastOperationAt) < GRACE_WINDOW_MS) return last
        return null
    }

    /** Marque une opération comme terminée (démarre la fenêtre de grâce). */
    fun markOperationDone(id: String) {
        if (id.isNotBlank()) { lastOperationId = id; lastOperationAt = System.currentTimeMillis() }
        currentOperationId = ""
    }

    // Package de l'app Maxit (Orange) — relancé à l'accueil après une opération Orange.
    var maxitPackage: String
        get() = _ctx?.let { prefs(it).getString("maxit_package", "com.orange.myorange.ci") } ?: "com.orange.myorange.ci"
        set(v) { _ctx?.let { prefs(it).edit().putString("maxit_package", v).apply() } }

    // Rejeter automatiquement tous les appels entrants (pour ne pas perturber les transactions).
    var rejectCalls: Boolean
        get() = _ctx?.let { prefs(it).getBoolean("reject_calls", true) } ?: true
        set(v) { _ctx?.let { prefs(it).edit().putBoolean("reject_calls", v).apply() } }

    private var _ctx: Context? = null
    fun init(ctx: Context) { _ctx = ctx.applicationContext }

    fun isConfigured(): Boolean = backendUrl.isNotBlank() && deviceToken.isNotBlank()
}

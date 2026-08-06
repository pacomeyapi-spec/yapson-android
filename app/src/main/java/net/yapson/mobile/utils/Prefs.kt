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

    // Auto-dépôt : la sonde initiale (200 F) a-t-elle réussi ?
    var autoProbeDone: Boolean
        get() = _ctx?.let { prefs(it).getBoolean("auto_probe_done", false) } ?: false
        set(v) { _ctx?.let { prefs(it).edit().putBoolean("auto_probe_done", v).apply() } }

    // Auto-dépôt : slot SIM pour lequel la sonde a été faite (la sonde se relance si on change de SIM)
    var autoProbeSlot: Int
        get() = _ctx?.let { prefs(it).getInt("auto_probe_slot", -1) } ?: -1
        set(v) { _ctx?.let { prefs(it).edit().putInt("auto_probe_slot", v).apply() } }

    // Auto-dépôt : sondes consécutives restées sans confirmation SMS (garde-fou anti-gaspillage)
    var autoProbeFails: Int
        get() = _ctx?.let { prefs(it).getInt("auto_probe_fails", 0) } ?: 0
        set(v) { _ctx?.let { prefs(it).edit().putInt("auto_probe_fails", v).apply() } }

    // Auto-dépôt : instant de la dernière sonde envoyée (espacement en cas d'échecs répétés)
    var autoLastProbeAt: Long
        get() = _ctx?.let { prefs(it).getLong("auto_last_probe_at", 0L) } ?: 0L
        set(v) { _ctx?.let { prefs(it).edit().putLong("auto_last_probe_at", v).apply() } }

    // Auto-dépôt : l'alerte "sondes sans confirmation" a-t-elle déjà été envoyée ?
    var autoProbeAlerted: Boolean
        get() = _ctx?.let { prefs(it).getBoolean("auto_probe_alerted", false) } ?: false
        set(v) { _ctx?.let { prefs(it).edit().putBoolean("auto_probe_alerted", v).apply() } }

    // Auto-dépôt : horodatage du dernier SMS +454 déjà traité (anti double-dépôt)
    var autoLastSmsTs: Long
        get() = _ctx?.let { prefs(it).getLong("auto_last_sms_ts", 0L) } ?: 0L
        set(v) { _ctx?.let { prefs(it).edit().putLong("auto_last_sms_ts", v).apply() } }

    private var _ctx: Context? = null
    fun init(ctx: Context) { _ctx = ctx.applicationContext }

    fun isConfigured(): Boolean = backendUrl.isNotBlank() && deviceToken.isNotBlank()
}

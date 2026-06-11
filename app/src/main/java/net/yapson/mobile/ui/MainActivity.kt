package net.yapson.mobile.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import net.yapson.mobile.R
import net.yapson.mobile.api.ApiClient
import net.yapson.mobile.databinding.ActivityMainBinding
import net.yapson.mobile.service.YapsonService
import net.yapson.mobile.utils.Prefs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val logs = mutableListOf<String>()

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra("msg") ?: return
            addLog(msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkPermissions()
        refreshStatus()
    }

    private fun setupUI() {
        // Bouton Config
        binding.btnConfig.setOnClickListener {
            startActivity(Intent(this, ConfigActivity::class.java))
        }

        // Bouton Start/Stop
        binding.btnToggle.setOnClickListener {
            if (YapsonService.isRunning) stopService()
            else startService()
        }

        // Bouton accès notifications (pour Wave)
        binding.btnNotifAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // Bouton activation service accessibilité USSD
        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Bouton test connexion
        binding.btnTest.setOnClickListener {
            testConnection()
        }
    }

    private fun startService() {
        if (!Prefs.isConfigured()) {
            Toast.makeText(this, "Configurez d'abord le backend", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, ConfigActivity::class.java))
            return
        }
        // Configurer le client API
        ApiClient.configure(Prefs.backendUrl, Prefs.deviceToken)
        Prefs.serviceEnabled = true

        val intent = Intent(this, YapsonService::class.java).apply {
            action = YapsonService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        addLog("▶️ Service démarré")
        refreshStatus()
    }

    private fun stopService() {
        Prefs.serviceEnabled = false
        startService(Intent(this, YapsonService::class.java).apply {
            action = YapsonService.ACTION_STOP
        })
        addLog("⏹️ Service arrêté")
        refreshStatus()
    }

    private fun refreshStatus() {
        val running = YapsonService.isRunning
        val configured = Prefs.isConfigured()
        val notifEnabled = isNotificationListenerEnabled()
        val accessibilityEnabled = isAccessibilityServiceEnabled()

        binding.tvStatus.text = if (running) "🟢 ACTIF" else "🔴 INACTIF"
        binding.tvStatusDetail.text = buildString {
            appendLine("Backend: ${if (configured) Prefs.backendUrl else "Non configuré"}")
            appendLine("Notifications Wave: ${if (notifEnabled) "Autorisées ✓" else "Non autorisées ⚠️"}")
            appendLine("USSD Auto: ${if (accessibilityEnabled) "Activé ✓" else "Non activé ⚠️"}")
            if (YapsonService.currentOperation != null) {
                val op = YapsonService.currentOperation!!
                appendLine("En cours: ${op.type} ${op.amount}F ${op.operator}")
            }
        }
        binding.btnToggle.text = if (running) "ARRÊTER" else "DÉMARRER"
        binding.btnNotifAccess.isEnabled = !notifEnabled
        binding.btnAccessibility.isEnabled = !accessibilityEnabled
    }

    private fun testConnection() {
        if (!Prefs.isConfigured()) {
            Toast.makeText(this, "Configurez d'abord le backend", Toast.LENGTH_SHORT).show()
            return
        }
        ApiClient.configure(Prefs.backendUrl, Prefs.deviceToken)
        addLog("🔗 Test connexion...")
        Thread {
            val ok = ApiClient.heartbeat()
            runOnUiThread {
                addLog(if (ok) "✅ Connexion OK" else "❌ Connexion échouée")
            }
        }.start()
    }

    private fun addLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        logs.add(0, "[$time] $msg")
        if (logs.size > 100) logs.removeAt(logs.size - 1)
        binding.tvLogs.text = logs.joinToString("\n")
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return listeners?.contains(packageName) == true
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(packageName)
    }

    private fun checkPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val denied = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (denied.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, denied.toTypedArray(), 100)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, IntentFilter("net.yapson.mobile.LOG"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, IntentFilter("net.yapson.mobile.LOG"))
        }
        refreshStatus()
        // Si le service tourne, reconfigurer l'API
        if (YapsonService.isRunning && Prefs.isConfigured()) {
            ApiClient.configure(Prefs.backendUrl, Prefs.deviceToken)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(logReceiver)
    }
}

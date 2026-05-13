package net.yapson.mobile.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import net.yapson.mobile.R
import net.yapson.mobile.databinding.ActivityConfigBinding
import net.yapson.mobile.utils.Prefs

class ConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.config_title)

        binding.etBackendUrl.setText(Prefs.backendUrl)
        binding.etDeviceToken.setText(Prefs.deviceToken)
        binding.etWavePackages.setText(Prefs.wavePackages)
        binding.etPollInterval.setText(Prefs.pollInterval.toString())

        binding.btnSave.setOnClickListener { save() }
    }

    private fun save() {
        val url = binding.etBackendUrl.text.toString().trim()
        val token = binding.etDeviceToken.text.toString().trim()
        val wavePackages = binding.etWavePackages.text.toString().trim()
        val interval = binding.etPollInterval.text.toString().toIntOrNull() ?: 5

        if (url.isBlank()) { binding.etBackendUrl.error = "URL obligatoire"; return }
        if (token.isBlank()) { binding.etDeviceToken.error = "Token obligatoire"; return }

        Prefs.backendUrl = url.trimEnd('/')
        Prefs.deviceToken = token
        Prefs.wavePackages = wavePackages.ifBlank { "com.wave.finance" }
        Prefs.pollInterval = interval.coerceIn(2, 60)

        Toast.makeText(this, "Configuration sauvegardee", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}

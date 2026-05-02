package com.zenpanel.kiosk

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zenpanel.kiosk.databinding.ActivitySettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: KioskPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = KioskPreferences(this)
        bindSettingsToUi(prefs.load())
        bindActions()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun bindActions() {
        binding.btnSaveSettings.setOnClickListener {
            saveSettings(reloadNow = false)
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        }

        binding.btnReloadDashboard.setOnClickListener {
            saveSettings(reloadNow = true)
            setResult(RESULT_OK, intentWithReload())
            finish()
        }

        binding.btnResetDefaults.setOnClickListener {
            val defaults = prefs.resetToDefaults()
            bindSettingsToUi(defaults)
            setResult(RESULT_OK, intentWithReload())
            Toast.makeText(this, "Defaults restored", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestUrl.setOnClickListener {
            val candidate = prefs.buildDashboardUrl(readSettingsFromUi())
            testUrl(candidate)
        }

        binding.btnSaveAdminPassword.setOnClickListener {
            saveAdminPassword()
        }

        binding.btnClearAdminPassword.setOnClickListener {
            prefs.clearAdminPassword()
            binding.inputAdminPassword.text?.clear()
            binding.inputAdminPasswordConfirm.text?.clear()
            updateAdminPasswordStatus()
            Toast.makeText(this, getString(R.string.password_cleared_success), Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindSettingsToUi(settings: KioskSettings) {
        when (settings.browserEngine) {
            BrowserEngine.GECKO -> binding.radioEngineGecko.isChecked = true
            BrowserEngine.WEBVIEW -> binding.radioEngineWebview.isChecked = true
            BrowserEngine.CHROMIUM_CUSTOM_TAB -> binding.radioEngineChromium.isChecked = true
        }
        binding.inputHomeAssistantUrl.setText(settings.homeAssistantUrl)
        binding.inputDashboardPath.setText(settings.dashboardPath)
        binding.inputReloadInterval.setText(settings.reloadIntervalSeconds.toString())
        binding.switchAppendKiosk.isChecked = settings.appendKiosk
        binding.switchKeepScreenOn.isChecked = settings.keepScreenOn
        binding.switchAutoStartBoot.isChecked = settings.autoStartOnBoot
        binding.switchFullscreen.isChecked = settings.fullscreen
        binding.currentUrlValue.text = prefs.buildDashboardUrl(settings)
        updateAdminPasswordStatus()
    }

    private fun readSettingsFromUi(): KioskSettings {
        val reloadInterval = binding.inputReloadInterval.text?.toString()?.trim()?.toIntOrNull()
            ?: KioskPreferences.DEFAULT_RELOAD_INTERVAL_SECONDS

        val selectedEngine = when (binding.radioEngineGroup.checkedRadioButtonId) {
            R.id.radio_engine_webview -> BrowserEngine.WEBVIEW
            R.id.radio_engine_chromium -> BrowserEngine.CHROMIUM_CUSTOM_TAB
            else -> BrowserEngine.GECKO
        }

        return KioskSettings(
            browserEngine = selectedEngine,
            homeAssistantUrl = binding.inputHomeAssistantUrl.text?.toString().orEmpty(),
            dashboardPath = binding.inputDashboardPath.text?.toString().orEmpty(),
            appendKiosk = binding.switchAppendKiosk.isChecked,
            reloadIntervalSeconds = reloadInterval,
            keepScreenOn = binding.switchKeepScreenOn.isChecked,
            autoStartOnBoot = binding.switchAutoStartBoot.isChecked,
            fullscreen = binding.switchFullscreen.isChecked
        )
    }

    private fun saveSettings(reloadNow: Boolean) {
        val settings = readSettingsFromUi()
        prefs.save(settings)
        binding.currentUrlValue.text = prefs.buildDashboardUrl(settings)

        if (reloadNow) {
            setResult(RESULT_OK, intentWithReload())
        }
    }

    private fun saveAdminPassword() {
        val password = binding.inputAdminPassword.text?.toString().orEmpty().trim()
        val confirm = binding.inputAdminPasswordConfirm.text?.toString().orEmpty().trim()

        if (password.length < MIN_ADMIN_PASSWORD_LENGTH) {
            Toast.makeText(this, getString(R.string.password_too_short), Toast.LENGTH_SHORT).show()
            return
        }
        if (password != confirm) {
            Toast.makeText(this, getString(R.string.password_mismatch), Toast.LENGTH_SHORT).show()
            return
        }

        prefs.setAdminPassword(password)
        binding.inputAdminPassword.text?.clear()
        binding.inputAdminPasswordConfirm.text?.clear()
        updateAdminPasswordStatus()
        Toast.makeText(this, getString(R.string.password_set_success), Toast.LENGTH_SHORT).show()
    }

    private fun updateAdminPasswordStatus() {
        binding.adminPasswordStatus.text = if (prefs.hasAdminPassword()) {
            getString(R.string.admin_password_set)
        } else {
            getString(R.string.admin_password_not_set)
        }
    }

    private fun testUrl(url: String) {
        binding.btnTestUrl.isEnabled = false
        binding.btnTestUrl.text = getString(R.string.testing_url)

        scope.launch {
            val response = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 6_000
                    connection.readTimeout = 6_000
                    connection.requestMethod = "GET"
                    connection.instanceFollowRedirects = true
                    connection.connect()
                    val code = connection.responseCode
                    connection.disconnect()
                    code
                }
            }

            binding.btnTestUrl.isEnabled = true
            binding.btnTestUrl.text = getString(R.string.test_url)

            response.onSuccess { code ->
                val ok = code in 200..399
                val message = if (ok) {
                    "Reachable (HTTP $code)"
                } else {
                    "URL responded with HTTP $code"
                }
                Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(
                    this@SettingsActivity,
                    "Test failed: ${error.message ?: "Unknown error"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun intentWithReload() = android.content.Intent().apply {
        putExtra(EXTRA_RELOAD_NOW, true)
    }

    companion object {
        const val EXTRA_RELOAD_NOW = "reload_now"
        private const val MIN_ADMIN_PASSWORD_LENGTH = 4
    }
}

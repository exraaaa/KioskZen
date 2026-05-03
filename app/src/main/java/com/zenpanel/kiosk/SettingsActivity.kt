package com.zenpanel.kiosk

import android.animation.ObjectAnimator
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
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
    private var activeDownloadId: Long = -1L
    private var downloadReceiverRegistered = false
    private var isBindingUi = false
    private var sectionTabs: List<SectionNav> = emptyList()
    private val backgroundAnimators = mutableListOf<ObjectAnimator>()

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (downloadId <= 0 || downloadId != activeDownloadId) return
            activeDownloadId = -1L
            installDownloadedApk(downloadId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = KioskPreferences(this)
        prefs.applyThemeMode()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindSettingsToUi(prefs.load())
        bindActions()
        setupSectionNavigation()
        val initialTab = savedInstanceState?.getInt(STATE_SELECTED_TAB, 0) ?: 0
        binding.tabSectionNav.getTabAt(initialTab)?.select()
        ensureDownloadReceiver()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED_TAB, binding.tabSectionNav.selectedTabPosition.coerceAtLeast(0))
    }

    override fun onDestroy() {
        stopBackgroundAnimations()
        super.onDestroy()
        scope.cancel()
        unregisterDownloadReceiverIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        startBackgroundAnimations()
    }

    override fun onPause() {
        stopBackgroundAnimations()
        super.onPause()
    }

    private fun bindActions() {
        binding.radioThemeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingUi) return@setOnCheckedChangeListener
            val selectedTheme = when (checkedId) {
                R.id.radio_theme_light -> ThemeMode.LIGHT
                R.id.radio_theme_dark -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
            applyThemeInstantly(selectedTheme)
        }

        binding.btnSaveSettings.setOnClickListener {
            if (saveSettings(reloadNow = false)) {
                Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnReloadDashboard.setOnClickListener {
            if (saveSettings(reloadNow = true)) {
                setResult(RESULT_OK, intentWithReload())
                finish()
            }
        }

        binding.btnResetDefaults.setOnClickListener {
            val previousTheme = prefs.load().themeMode
            val defaults = prefs.resetToDefaults()
            bindSettingsToUi(defaults)
            setResult(RESULT_OK, intentWithReload())
            if (previousTheme != defaults.themeMode) {
                prefs.applyThemeMode()
                recreate()
            }
            Toast.makeText(this, getString(R.string.defaults_restored), Toast.LENGTH_SHORT).show()
        }

        binding.btnTestUrl.setOnClickListener {
            val candidate = prefs.buildDashboardUrl(readSettingsFromUi())
            testUrl(candidate)
        }

        binding.btnSaveAdminPassword.setOnClickListener { saveAdminPassword() }

        binding.btnClearAdminPassword.setOnClickListener {
            prefs.clearAdminPassword()
            binding.inputAdminPassword.text?.clear()
            binding.inputAdminPasswordConfirm.text?.clear()
            updateAdminPasswordStatus()
            Toast.makeText(this, getString(R.string.password_cleared_success), Toast.LENGTH_SHORT).show()
        }

        binding.btnCheckUpdates.setOnClickListener {
            val uiSettings = readSettingsFromUi()
            if (!KioskPreferences.isValidRepoSlug(uiSettings.updatesRepo)) {
                Toast.makeText(this, getString(R.string.invalid_repo_slug), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            checkForUpdates(uiSettings.updatesRepo)
        }

        binding.btnExportConfig.setOnClickListener {
            val json = prefs.exportSettingsJson(readSettingsFromUi())
            shareConfigJson(json)
        }

        binding.btnImportConfig.setOnClickListener {
            showImportDialog()
        }

        binding.btnDiagnostics.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        binding.btnExitKiosk.setOnClickListener {
            maybeExitKiosk()
        }
    }

    private fun setupSectionNavigation() {
        sectionTabs = listOf(
            SectionNav(getString(R.string.nav_display), binding.sectionBrowserAdmin, android.R.drawable.ic_menu_manage),
            SectionNav(getString(R.string.nav_dashboard), binding.sectionDashboard, android.R.drawable.ic_menu_view),
            SectionNav(getString(R.string.nav_engine), binding.sectionEngine, android.R.drawable.ic_menu_compass),
            SectionNav(getString(R.string.nav_watchdog), binding.sectionWatchdog, android.R.drawable.ic_lock_idle_alarm),
            SectionNav(getString(R.string.nav_automation), binding.sectionAutomation, android.R.drawable.ic_menu_recent_history),
            SectionNav(getString(R.string.nav_updates), binding.sectionUpdates, android.R.drawable.ic_popup_sync),
            SectionNav(getString(R.string.nav_actions), binding.sectionActions, android.R.drawable.ic_menu_send)
        )

        binding.tabSectionNav.removeAllTabs()
        sectionTabs.forEach { section ->
            binding.tabSectionNav.addTab(
                binding.tabSectionNav.newTab()
                    .setText(section.label)
                    .setIcon(section.iconRes)
            )
        }

        binding.tabSectionNav.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showOnlySelectedSection(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) {
                onTabSelected(tab)
            }
        })

        showOnlySelectedSection(0)
    }

    private fun showOnlySelectedSection(selectedIndex: Int) {
        sectionTabs.forEachIndexed { index, section ->
            section.view.visibility = if (index == selectedIndex) View.VISIBLE else View.GONE
        }
        binding.settingsScroll.post {
            binding.settingsScroll.scrollTo(0, 0)
        }
    }

    private fun startBackgroundAnimations() {
        if (backgroundAnimators.isNotEmpty()) return

        fun animate(
            view: View,
            property: android.util.Property<View, Float>,
            values: FloatArray,
            durationMs: Long,
            delayMs: Long = 0L
        ) {
            val animator = ObjectAnimator.ofFloat(view, property, *values).apply {
                duration = durationMs
                startDelay = delayMs
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
            backgroundAnimators += animator
        }

        animate(binding.orbPrimary, View.TRANSLATION_X, floatArrayOf(-26f, 24f), 10_500L)
        animate(binding.orbPrimary, View.TRANSLATION_Y, floatArrayOf(-18f, 22f), 9_300L, 700L)
        animate(binding.orbSecondary, View.TRANSLATION_X, floatArrayOf(20f, -22f), 11_200L, 400L)
        animate(binding.orbSecondary, View.TRANSLATION_Y, floatArrayOf(18f, -20f), 10_100L, 1_100L)
        animate(binding.orbPrimary, View.ALPHA, floatArrayOf(0.72f, 1f), 7_400L)
        animate(binding.orbSecondary, View.ALPHA, floatArrayOf(0.64f, 0.96f), 8_600L)
    }

    private fun stopBackgroundAnimations() {
        backgroundAnimators.forEach { it.cancel() }
        backgroundAnimators.clear()
    }

    private fun bindSettingsToUi(settings: KioskSettings) {
        isBindingUi = true
        when (settings.browserEngine) {
            BrowserEngine.GECKO -> binding.radioEngineGecko.isChecked = true
            BrowserEngine.WEBVIEW -> binding.radioEngineWebview.isChecked = true
            BrowserEngine.CHROMIUM_CUSTOM_TAB -> binding.radioEngineChromium.isChecked = true
        }
        when (settings.themeMode) {
            ThemeMode.SYSTEM -> binding.radioThemeSystem.isChecked = true
            ThemeMode.LIGHT -> binding.radioThemeLight.isChecked = true
            ThemeMode.DARK -> binding.radioThemeDark.isChecked = true
        }

        binding.inputHomeAssistantUrl.setText(settings.homeAssistantUrl)
        binding.inputDashboardPath.setText(settings.dashboardPath)
        binding.inputReloadInterval.setText(settings.reloadIntervalSeconds.toString())
        binding.switchAppendKiosk.isChecked = settings.appendKiosk
        binding.switchKeepScreenOn.isChecked = settings.keepScreenOn
        binding.switchAutoStartBoot.isChecked = settings.autoStartOnBoot
        binding.switchFullscreen.isChecked = settings.fullscreen
        binding.switchLockTaskMode.isChecked = settings.lockTaskMode
        binding.switchAutoReloadOnFailure.isChecked = settings.autoReloadOnFailure

        binding.switchWatchdogEnabled.isChecked = settings.watchdogEnabled
        binding.inputWatchdogPath.setText(settings.watchdogPingPath)
        binding.inputWatchdogInterval.setText(settings.watchdogPingIntervalSeconds.toString())

        binding.switchUpdatesEnabled.isChecked = settings.updatesEnabled
        binding.inputUpdatesRepo.setText(settings.updatesRepo)
        binding.inputUpdateCheckInterval.setText(settings.updateCheckIntervalHours.toString())
        binding.updateStatusValue.text = composeUpdateStatusText()

        binding.switchAllowMixedContent.isChecked = settings.allowMixedContent
        binding.switchAllowThirdPartyCookies.isChecked = settings.allowThirdPartyCookies
        binding.switchAutoplay.isChecked = settings.autoplayEnabled
        binding.switchDesktopMode.isChecked = settings.desktopMode
        binding.inputCustomUserAgent.setText(settings.customUserAgent)

        binding.switchRequirePasswordForExitOnly.isChecked = settings.requirePasswordForExitOnly
        binding.inputAdminUnlockTimeout.setText(settings.adminUnlockTimeoutMinutes.toString())

        binding.switchMaintenanceEnabled.isChecked = settings.maintenanceEnabled
        binding.inputMaintenanceHour.setText(settings.maintenanceHour.toString())
        binding.inputMaintenanceMinute.setText(settings.maintenanceMinute.toString())

        binding.switchAmbientMode.isChecked = settings.ambientModeEnabled
        binding.inputAmbientDimAfter.setText(settings.ambientDimAfterSeconds.toString())
        binding.inputAmbientBrightness.setText(settings.ambientBrightnessPercent.toString())

        binding.currentUrlValue.text = prefs.buildDashboardUrl(settings)
        updateAdminPasswordStatus()
        isBindingUi = false
    }

    private fun applyThemeInstantly(targetTheme: ThemeMode) {
        val current = prefs.load()
        if (current.themeMode == targetTheme) return

        prefs.save(current.copy(themeMode = targetTheme))
        prefs.applyThemeMode()
        setResult(RESULT_OK, intentWithReload())
        recreate()
    }

    private fun readSettingsFromUi(): KioskSettings {
        val current = prefs.load()
        val reloadInterval = binding.inputReloadInterval.text?.toString()?.trim()?.toIntOrNull()
            ?: current.reloadIntervalSeconds
        val watchdogInterval = binding.inputWatchdogInterval.text?.toString()?.trim()?.toIntOrNull()
            ?: current.watchdogPingIntervalSeconds
        val updateInterval = binding.inputUpdateCheckInterval.text?.toString()?.trim()?.toIntOrNull()
            ?: current.updateCheckIntervalHours
        val unlockTimeout = binding.inputAdminUnlockTimeout.text?.toString()?.trim()?.toIntOrNull()
            ?: current.adminUnlockTimeoutMinutes
        val maintenanceHour = binding.inputMaintenanceHour.text?.toString()?.trim()?.toIntOrNull()
            ?: current.maintenanceHour
        val maintenanceMinute = binding.inputMaintenanceMinute.text?.toString()?.trim()?.toIntOrNull()
            ?: current.maintenanceMinute
        val ambientDimAfter = binding.inputAmbientDimAfter.text?.toString()?.trim()?.toIntOrNull()
            ?: current.ambientDimAfterSeconds
        val ambientBrightness = binding.inputAmbientBrightness.text?.toString()?.trim()?.toIntOrNull()
            ?: current.ambientBrightnessPercent

        val selectedEngine = when (binding.radioEngineGroup.checkedRadioButtonId) {
            R.id.radio_engine_webview -> BrowserEngine.WEBVIEW
            R.id.radio_engine_chromium -> BrowserEngine.CHROMIUM_CUSTOM_TAB
            else -> BrowserEngine.GECKO
        }
        val selectedTheme = when (binding.radioThemeGroup.checkedRadioButtonId) {
            R.id.radio_theme_light -> ThemeMode.LIGHT
            R.id.radio_theme_dark -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }

        return current.copy(
            browserEngine = selectedEngine,
            themeMode = selectedTheme,
            homeAssistantUrl = binding.inputHomeAssistantUrl.text?.toString().orEmpty(),
            dashboardPath = binding.inputDashboardPath.text?.toString().orEmpty(),
            appendKiosk = binding.switchAppendKiosk.isChecked,
            reloadIntervalSeconds = reloadInterval,
            keepScreenOn = binding.switchKeepScreenOn.isChecked,
            autoStartOnBoot = binding.switchAutoStartBoot.isChecked,
            fullscreen = binding.switchFullscreen.isChecked,
            lockTaskMode = binding.switchLockTaskMode.isChecked,
            autoReloadOnFailure = binding.switchAutoReloadOnFailure.isChecked,
            watchdogEnabled = binding.switchWatchdogEnabled.isChecked,
            watchdogPingPath = binding.inputWatchdogPath.text?.toString().orEmpty(),
            watchdogPingIntervalSeconds = watchdogInterval,
            updatesEnabled = binding.switchUpdatesEnabled.isChecked,
            updatesRepo = binding.inputUpdatesRepo.text?.toString().orEmpty(),
            updateCheckIntervalHours = updateInterval,
            allowMixedContent = binding.switchAllowMixedContent.isChecked,
            allowThirdPartyCookies = binding.switchAllowThirdPartyCookies.isChecked,
            autoplayEnabled = binding.switchAutoplay.isChecked,
            desktopMode = binding.switchDesktopMode.isChecked,
            customUserAgent = binding.inputCustomUserAgent.text?.toString().orEmpty(),
            requirePasswordForExitOnly = binding.switchRequirePasswordForExitOnly.isChecked,
            adminUnlockTimeoutMinutes = unlockTimeout,
            scheduleProfilesEnabled = false,
            homeStartHour = current.homeStartHour,
            wallStartHour = current.wallStartHour,
            nightStartHour = current.nightStartHour,
            profileHomePath = current.profileHomePath,
            profileWallPath = current.profileWallPath,
            profileNightPath = current.profileNightPath,
            maintenanceEnabled = binding.switchMaintenanceEnabled.isChecked,
            maintenanceHour = maintenanceHour,
            maintenanceMinute = maintenanceMinute,
            ambientModeEnabled = binding.switchAmbientMode.isChecked,
            ambientDimAfterSeconds = ambientDimAfter,
            ambientBrightnessPercent = ambientBrightness
        )
    }

    private fun saveSettings(reloadNow: Boolean): Boolean {
        val previousSettings = prefs.load()
        val settings = readSettingsFromUi()
        val normalizedBaseUrl = KioskPreferences.normalizeBaseUrl(settings.homeAssistantUrl)
        if (!KioskPreferences.isHttpOrHttpsUrl(normalizedBaseUrl)) {
            Toast.makeText(this, getString(R.string.invalid_home_assistant_url), Toast.LENGTH_SHORT)
                .show()
            return false
        }

        if (settings.updatesEnabled && !KioskPreferences.isValidRepoSlug(settings.updatesRepo)) {
            Toast.makeText(this, getString(R.string.invalid_repo_slug), Toast.LENGTH_SHORT).show()
            return false
        }

        prefs.save(settings)
        if (previousSettings.themeMode != settings.themeMode) {
            prefs.applyThemeMode()
            if (!reloadNow) {
                recreate()
            }
        }
        binding.currentUrlValue.text = prefs.buildDashboardUrl(settings)
        if (normalizedBaseUrl.startsWith("http://", ignoreCase = true)) {
            Toast.makeText(this, getString(R.string.warning_cleartext_http), Toast.LENGTH_LONG).show()
        }

        if (reloadNow) {
            setResult(RESULT_OK, intentWithReload())
        }
        return true
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
        if (!KioskPreferences.isHttpOrHttpsUrl(url)) {
            Toast.makeText(this, getString(R.string.invalid_home_assistant_url), Toast.LENGTH_SHORT)
                .show()
            return
        }

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
                    getString(R.string.reachable_http, code)
                } else {
                    getString(R.string.responded_http, code)
                }
                Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.test_failed, error.message ?: getString(R.string.unknown_error)),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun checkForUpdates(repoSlug: String) {
        binding.btnCheckUpdates.isEnabled = false
        binding.updateStatusValue.text = getString(R.string.checking_updates)

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                UpdateManager.fetchLatestRelease(repoSlug)
            }

            binding.btnCheckUpdates.isEnabled = true
            result.onSuccess { release ->
                val currentVersion = currentVersionName()
                val currentLabel = UpdateManager.normalizedVersionLabel(currentVersion)
                val latestLabel = UpdateManager.normalizedVersionLabel(release.tagName)
                val hasUpdate = UpdateManager.isNewerRelease(currentVersion, release.tagName)
                if (!hasUpdate) {
                    val message = getString(R.string.up_to_date_version_detailed, currentLabel, latestLabel)
                    binding.updateStatusValue.text = composeUpdateStatusText(message)
                    prefs.recordUpdateState(message)
                    Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show()
                    return@onSuccess
                }

                val asset = release.apkAsset
                if (asset == null) {
                    val message = getString(R.string.update_found_no_apk, latestLabel)
                    binding.updateStatusValue.text = composeUpdateStatusText(message)
                    prefs.recordUpdateState(message)
                    return@onSuccess
                }

                val status = getString(R.string.update_available_status, latestLabel, asset.name)
                binding.updateStatusValue.text = composeUpdateStatusText(status)
                prefs.recordUpdateState(status)
                showDownloadUpdatePrompt(release)
            }.onFailure { error ->
                val message = getString(R.string.update_check_failed, error.message ?: getString(R.string.unknown_error))
                binding.updateStatusValue.text = composeUpdateStatusText(message)
                prefs.recordUpdateState(message)
                Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun composeUpdateStatusText(lastStatus: String? = prefs.diagnosticsSnapshot().lastUpdateState): String {
        val installed = UpdateManager.normalizedVersionLabel(currentVersionName())
        val status = lastStatus?.trim().orEmpty()
        if (status.isBlank() || status.equals("never checked", ignoreCase = true)) {
            return getString(R.string.update_status_installed, installed)
        }
        return getString(R.string.update_status_with_last, installed, status)
    }

    private fun showDownloadUpdatePrompt(release: ReleaseInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_available_title, release.tagName))
            .setMessage(getString(R.string.update_download_prompt, release.apkAsset?.name ?: "APK"))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.download_update) { _, _ ->
                val downloadId = UpdateManager.enqueueDownload(
                    context = this,
                    releaseInfo = release,
                    appLabel = getString(R.string.app_name)
                )
                activeDownloadId = downloadId
                Toast.makeText(this, getString(R.string.update_download_started), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun installDownloadedApk(downloadId: Long) {
        val uri = UpdateManager.queryDownloadedUri(this, downloadId)
        if (uri == null) {
            Toast.makeText(this, getString(R.string.update_download_failed), Toast.LENGTH_SHORT).show()
            return
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(installIntent)
    }

    private fun shareConfigJson(json: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "KioskZen config")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.export_config)))
    }

    private fun showImportDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.paste_config_json)
            minLines = 8
            maxLines = 12
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_config)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.import_config) { _, _ ->
                val raw = input.text?.toString().orEmpty().trim()
                if (raw.isBlank()) {
                    Toast.makeText(this, getString(R.string.empty_config), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val previousTheme = prefs.load().themeMode
                runCatching { prefs.importSettingsJson(raw) }
                    .onSuccess { imported ->
                        bindSettingsToUi(imported)
                        if (previousTheme != imported.themeMode) {
                            prefs.applyThemeMode()
                            recreate()
                        }
                        setResult(RESULT_OK, intentWithReload())
                        Toast.makeText(this, getString(R.string.config_imported), Toast.LENGTH_SHORT).show()
                    }
                    .onFailure { error ->
                        Toast.makeText(
                            this,
                            getString(R.string.import_failed, error.message ?: getString(R.string.unknown_error)),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            }
            .show()
    }

    private fun maybeExitKiosk() {
        val settings = prefs.load()
        val requiresPassword = settings.requirePasswordForExitOnly && prefs.hasAdminPassword()
        if (!requiresPassword) {
            setResult(RESULT_OK, intentWithExit())
            finish()
            return
        }

        val input = EditText(this).apply {
            hint = getString(R.string.admin_password_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exit_kiosk)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.unlock, null)
            .create()

        dialog.setOnShowListener {
            val unlockButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val unlockLabel = getString(R.string.unlock)
            unlockButton.setOnClickListener {
                val candidate = input.text?.toString().orEmpty()
                if (candidate.isBlank()) {
                    input.error = getString(R.string.invalid_admin_password)
                    return@setOnClickListener
                }

                unlockButton.isEnabled = false
                unlockButton.text = getString(R.string.verifying_password)
                input.error = null

                scope.launch {
                    val isValid = withContext(Dispatchers.Default) { prefs.verifyAdminPassword(candidate) }
                    if (!dialog.isShowing) return@launch

                    unlockButton.isEnabled = true
                    unlockButton.text = unlockLabel
                    if (isValid) {
                        prefs.markAdminUnlockedNow()
                        dialog.dismiss()
                        setResult(RESULT_OK, intentWithExit())
                        finish()
                    } else {
                        input.error = getString(R.string.invalid_admin_password)
                        input.text?.clear()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun ensureDownloadReceiver() {
        if (downloadReceiverRegistered) return
        registerReceiver(
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        )
        downloadReceiverRegistered = true
    }

    private fun unregisterDownloadReceiverIfNeeded() {
        if (!downloadReceiverRegistered) return
        runCatching { unregisterReceiver(downloadReceiver) }
        downloadReceiverRegistered = false
    }

    private fun intentWithReload() = Intent().apply {
        putExtra(EXTRA_RELOAD_NOW, true)
    }

    private fun intentWithExit() = Intent().apply {
        putExtra(EXTRA_EXIT_APP, true)
    }

    private data class SectionNav(
        val label: String,
        val view: View,
        val iconRes: Int
    )

    companion object {
        const val EXTRA_RELOAD_NOW = "reload_now"
        const val EXTRA_EXIT_APP = "exit_app"
        private const val MIN_ADMIN_PASSWORD_LENGTH = 4
        private const val STATE_SELECTED_TAB = "state_selected_tab"
    }

    private fun currentVersionName(): String {
        return runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
        }.getOrDefault("0.0.0")
    }
}

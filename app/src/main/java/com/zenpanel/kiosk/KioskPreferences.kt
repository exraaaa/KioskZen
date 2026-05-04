package com.zenpanel.kiosk

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.appcompat.app.AppCompatDelegate
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class BrowserEngine {
    GECKO,
    WEBVIEW,
    CHROMIUM_CUSTOM_TAB;

    companion object {
        fun fromStoredValue(value: String?): BrowserEngine {
            return entries.firstOrNull { it.name == value } ?: GECKO
        }
    }
}

enum class ThemeMode(val appCompatMode: Int) {
    SYSTEM(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT(AppCompatDelegate.MODE_NIGHT_NO),
    DARK(AppCompatDelegate.MODE_NIGHT_YES);

    companion object {
        fun fromStoredValue(value: String?): ThemeMode {
            return entries.firstOrNull { it.name == value } ?: SYSTEM
        }
    }
}

data class KioskSettings(
    val browserEngine: BrowserEngine,
    val homeAssistantUrl: String,
    val dashboardPath: String,
    val appendKiosk: Boolean,
    val reloadIntervalSeconds: Int,
    val keepScreenOn: Boolean,
    val autoStartOnBoot: Boolean,
    val fullscreen: Boolean,
    val themeMode: ThemeMode,
    val lockTaskMode: Boolean,
    val autoReloadOnFailure: Boolean,
    val watchdogEnabled: Boolean,
    val watchdogPingPath: String,
    val watchdogPingIntervalSeconds: Int,
    val updatesEnabled: Boolean,
    val updatesRepo: String,
    val updateCheckIntervalHours: Int,
    val allowMixedContent: Boolean,
    val allowThirdPartyCookies: Boolean,
    val autoplayEnabled: Boolean,
    val desktopMode: Boolean,
    val customUserAgent: String,
    val requirePasswordForExitOnly: Boolean,
    val adminUnlockTimeoutMinutes: Int,
    val scheduleProfilesEnabled: Boolean,
    val homeStartHour: Int,
    val wallStartHour: Int,
    val nightStartHour: Int,
    val profileHomePath: String,
    val profileWallPath: String,
    val profileNightPath: String,
    val maintenanceEnabled: Boolean,
    val maintenanceHour: Int,
    val maintenanceMinute: Int,
    val ambientModeEnabled: Boolean,
    val ambientDimAfterSeconds: Int,
    val ambientBrightnessPercent: Int,
    val presenceWakeEnabled: Boolean,
    val presenceWakeCooldownSeconds: Int,
    val autoDiscoverHomeAssistant: Boolean,
    val localControlEnabled: Boolean,
    val localControlPort: Int,
    val localControlPreviewEnabled: Boolean
)

data class DiagnosticsSnapshot(
    val lastEngine: String,
    val lastUrl: String,
    val lastLoadStatus: String,
    val lastCrashReason: String,
    val lastCrashAtMillis: Long,
    val lastNetworkState: String,
    val lastWatchdogState: String,
    val lastUpdateState: String
)

class KioskPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun shouldShowStartupCameraPrompt(): Boolean {
        return !prefs.getBoolean(KEY_STARTUP_CAMERA_PROMPT_SHOWN, false)
    }

    fun markStartupCameraPromptShown() {
        prefs.edit().putBoolean(KEY_STARTUP_CAMERA_PROMPT_SHOWN, true).apply()
    }

    fun load(): KioskSettings {
        return KioskSettings(
            browserEngine = BrowserEngine.fromStoredValue(
                prefs.getString(KEY_BROWSER_ENGINE, DEFAULT_BROWSER_ENGINE.name)
            ),
            homeAssistantUrl = prefs.getString(KEY_HOME_ASSISTANT_URL, DEFAULT_HOME_ASSISTANT_URL)
                ?: DEFAULT_HOME_ASSISTANT_URL,
            dashboardPath = prefs.getString(KEY_DASHBOARD_PATH, DEFAULT_DASHBOARD_PATH)
                ?: DEFAULT_DASHBOARD_PATH,
            appendKiosk = prefs.getBoolean(KEY_APPEND_KIOSK, DEFAULT_APPEND_KIOSK),
            reloadIntervalSeconds = clampInt(
                prefs.getInt(KEY_RELOAD_INTERVAL_SECONDS, DEFAULT_RELOAD_INTERVAL_SECONDS),
                MIN_RELOAD_INTERVAL_SECONDS,
                MAX_RELOAD_INTERVAL_SECONDS
            ),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, DEFAULT_KEEP_SCREEN_ON),
            autoStartOnBoot = prefs.getBoolean(KEY_AUTOSTART_ON_BOOT, DEFAULT_AUTOSTART_ON_BOOT),
            fullscreen = prefs.getBoolean(KEY_FULLSCREEN, DEFAULT_FULLSCREEN),
            themeMode = ThemeMode.fromStoredValue(
                prefs.getString(KEY_THEME_MODE, DEFAULT_THEME_MODE.name)
            ),
            lockTaskMode = prefs.getBoolean(KEY_LOCK_TASK_MODE, DEFAULT_LOCK_TASK_MODE),
            autoReloadOnFailure = prefs.getBoolean(KEY_AUTO_RELOAD_ON_FAILURE, DEFAULT_AUTO_RELOAD_ON_FAILURE),
            watchdogEnabled = prefs.getBoolean(KEY_WATCHDOG_ENABLED, DEFAULT_WATCHDOG_ENABLED),
            watchdogPingPath = prefs.getString(KEY_WATCHDOG_PING_PATH, DEFAULT_WATCHDOG_PING_PATH)
                ?: DEFAULT_WATCHDOG_PING_PATH,
            watchdogPingIntervalSeconds = clampInt(
                prefs.getInt(KEY_WATCHDOG_PING_INTERVAL_SECONDS, DEFAULT_WATCHDOG_PING_INTERVAL_SECONDS),
                MIN_WATCHDOG_PING_INTERVAL_SECONDS,
                MAX_WATCHDOG_PING_INTERVAL_SECONDS
            ),
            updatesEnabled = prefs.getBoolean(KEY_UPDATES_ENABLED, DEFAULT_UPDATES_ENABLED),
            updatesRepo = migrateRepoSlug(
                prefs.getString(KEY_UPDATES_REPO, DEFAULT_UPDATES_REPO) ?: DEFAULT_UPDATES_REPO
            ),
            updateCheckIntervalHours = clampInt(
                prefs.getInt(KEY_UPDATE_CHECK_INTERVAL_HOURS, DEFAULT_UPDATE_CHECK_INTERVAL_HOURS),
                MIN_UPDATE_CHECK_INTERVAL_HOURS,
                MAX_UPDATE_CHECK_INTERVAL_HOURS
            ),
            allowMixedContent = prefs.getBoolean(KEY_ALLOW_MIXED_CONTENT, DEFAULT_ALLOW_MIXED_CONTENT),
            allowThirdPartyCookies = prefs.getBoolean(
                KEY_ALLOW_THIRD_PARTY_COOKIES,
                DEFAULT_ALLOW_THIRD_PARTY_COOKIES
            ),
            autoplayEnabled = prefs.getBoolean(KEY_AUTOPLAY_ENABLED, DEFAULT_AUTOPLAY_ENABLED),
            desktopMode = prefs.getBoolean(KEY_DESKTOP_MODE, DEFAULT_DESKTOP_MODE),
            customUserAgent = prefs.getString(KEY_CUSTOM_USER_AGENT, DEFAULT_CUSTOM_USER_AGENT)
                ?: DEFAULT_CUSTOM_USER_AGENT,
            requirePasswordForExitOnly = prefs.getBoolean(
                KEY_REQUIRE_PASSWORD_FOR_EXIT_ONLY,
                DEFAULT_REQUIRE_PASSWORD_FOR_EXIT_ONLY
            ),
            adminUnlockTimeoutMinutes = clampInt(
                prefs.getInt(KEY_ADMIN_UNLOCK_TIMEOUT_MINUTES, DEFAULT_ADMIN_UNLOCK_TIMEOUT_MINUTES),
                MIN_ADMIN_UNLOCK_TIMEOUT_MINUTES,
                MAX_ADMIN_UNLOCK_TIMEOUT_MINUTES
            ),
            scheduleProfilesEnabled = prefs.getBoolean(
                KEY_SCHEDULE_PROFILES_ENABLED,
                DEFAULT_SCHEDULE_PROFILES_ENABLED
            ),
            homeStartHour = clampInt(
                prefs.getInt(KEY_HOME_START_HOUR, DEFAULT_HOME_START_HOUR),
                MIN_HOUR,
                MAX_HOUR
            ),
            wallStartHour = clampInt(
                prefs.getInt(KEY_WALL_START_HOUR, DEFAULT_WALL_START_HOUR),
                MIN_HOUR,
                MAX_HOUR
            ),
            nightStartHour = clampInt(
                prefs.getInt(KEY_NIGHT_START_HOUR, DEFAULT_NIGHT_START_HOUR),
                MIN_HOUR,
                MAX_HOUR
            ),
            profileHomePath = prefs.getString(KEY_PROFILE_HOME_PATH, DEFAULT_PROFILE_HOME_PATH)
                ?: DEFAULT_PROFILE_HOME_PATH,
            profileWallPath = prefs.getString(KEY_PROFILE_WALL_PATH, DEFAULT_PROFILE_WALL_PATH)
                ?: DEFAULT_PROFILE_WALL_PATH,
            profileNightPath = prefs.getString(KEY_PROFILE_NIGHT_PATH, DEFAULT_PROFILE_NIGHT_PATH)
                ?: DEFAULT_PROFILE_NIGHT_PATH,
            maintenanceEnabled = prefs.getBoolean(KEY_MAINTENANCE_ENABLED, DEFAULT_MAINTENANCE_ENABLED),
            maintenanceHour = clampInt(
                prefs.getInt(KEY_MAINTENANCE_HOUR, DEFAULT_MAINTENANCE_HOUR),
                MIN_HOUR,
                MAX_HOUR
            ),
            maintenanceMinute = clampInt(
                prefs.getInt(KEY_MAINTENANCE_MINUTE, DEFAULT_MAINTENANCE_MINUTE),
                MIN_MINUTE,
                MAX_MINUTE
            ),
            ambientModeEnabled = prefs.getBoolean(KEY_AMBIENT_MODE_ENABLED, DEFAULT_AMBIENT_MODE_ENABLED),
            ambientDimAfterSeconds = clampInt(
                prefs.getInt(KEY_AMBIENT_DIM_AFTER_SECONDS, DEFAULT_AMBIENT_DIM_AFTER_SECONDS),
                MIN_AMBIENT_DIM_AFTER_SECONDS,
                MAX_AMBIENT_DIM_AFTER_SECONDS
            ),
            ambientBrightnessPercent = clampInt(
                prefs.getInt(KEY_AMBIENT_BRIGHTNESS_PERCENT, DEFAULT_AMBIENT_BRIGHTNESS_PERCENT),
                MIN_AMBIENT_BRIGHTNESS_PERCENT,
                MAX_AMBIENT_BRIGHTNESS_PERCENT
            ),
            presenceWakeEnabled = prefs.getBoolean(
                KEY_PRESENCE_WAKE_ENABLED,
                DEFAULT_PRESENCE_WAKE_ENABLED
            ),
            presenceWakeCooldownSeconds = clampInt(
                prefs.getInt(
                    KEY_PRESENCE_WAKE_COOLDOWN_SECONDS,
                    DEFAULT_PRESENCE_WAKE_COOLDOWN_SECONDS
                ),
                MIN_PRESENCE_WAKE_COOLDOWN_SECONDS,
                MAX_PRESENCE_WAKE_COOLDOWN_SECONDS
            ),
            autoDiscoverHomeAssistant = prefs.getBoolean(
                KEY_AUTO_DISCOVER_HOME_ASSISTANT,
                DEFAULT_AUTO_DISCOVER_HOME_ASSISTANT
            ),
            localControlEnabled = prefs.getBoolean(
                KEY_LOCAL_CONTROL_ENABLED,
                DEFAULT_LOCAL_CONTROL_ENABLED
            ),
            localControlPort = clampInt(
                prefs.getInt(KEY_LOCAL_CONTROL_PORT, DEFAULT_LOCAL_CONTROL_PORT),
                MIN_LOCAL_CONTROL_PORT,
                MAX_LOCAL_CONTROL_PORT
            ),
            localControlPreviewEnabled = prefs.getBoolean(
                KEY_LOCAL_CONTROL_PREVIEW_ENABLED,
                DEFAULT_LOCAL_CONTROL_PREVIEW_ENABLED
            )
        )
    }

    fun save(settings: KioskSettings) {
        prefs.edit()
            .putString(KEY_BROWSER_ENGINE, settings.browserEngine.name)
            .putString(KEY_HOME_ASSISTANT_URL, normalizeBaseUrl(settings.homeAssistantUrl))
            .putString(KEY_DASHBOARD_PATH, normalizeDashboardPath(settings.dashboardPath))
            .putBoolean(KEY_APPEND_KIOSK, settings.appendKiosk)
            .putInt(
                KEY_RELOAD_INTERVAL_SECONDS,
                clampInt(
                    settings.reloadIntervalSeconds,
                    MIN_RELOAD_INTERVAL_SECONDS,
                    MAX_RELOAD_INTERVAL_SECONDS
                )
            )
            .putBoolean(KEY_KEEP_SCREEN_ON, settings.keepScreenOn)
            .putBoolean(KEY_AUTOSTART_ON_BOOT, settings.autoStartOnBoot)
            .putBoolean(KEY_FULLSCREEN, settings.fullscreen)
            .putString(KEY_THEME_MODE, settings.themeMode.name)
            .putBoolean(KEY_LOCK_TASK_MODE, settings.lockTaskMode)
            .putBoolean(KEY_AUTO_RELOAD_ON_FAILURE, settings.autoReloadOnFailure)
            .putBoolean(KEY_WATCHDOG_ENABLED, settings.watchdogEnabled)
            .putString(KEY_WATCHDOG_PING_PATH, normalizeDashboardPath(settings.watchdogPingPath))
            .putInt(
                KEY_WATCHDOG_PING_INTERVAL_SECONDS,
                clampInt(
                    settings.watchdogPingIntervalSeconds,
                    MIN_WATCHDOG_PING_INTERVAL_SECONDS,
                    MAX_WATCHDOG_PING_INTERVAL_SECONDS
                )
            )
            .putBoolean(KEY_UPDATES_ENABLED, settings.updatesEnabled)
            .putString(KEY_UPDATES_REPO, migrateRepoSlug(settings.updatesRepo))
            .putInt(
                KEY_UPDATE_CHECK_INTERVAL_HOURS,
                clampInt(
                    settings.updateCheckIntervalHours,
                    MIN_UPDATE_CHECK_INTERVAL_HOURS,
                    MAX_UPDATE_CHECK_INTERVAL_HOURS
                )
            )
            .putBoolean(KEY_ALLOW_MIXED_CONTENT, settings.allowMixedContent)
            .putBoolean(KEY_ALLOW_THIRD_PARTY_COOKIES, settings.allowThirdPartyCookies)
            .putBoolean(KEY_AUTOPLAY_ENABLED, settings.autoplayEnabled)
            .putBoolean(KEY_DESKTOP_MODE, settings.desktopMode)
            .putString(KEY_CUSTOM_USER_AGENT, settings.customUserAgent.trim())
            .putBoolean(KEY_REQUIRE_PASSWORD_FOR_EXIT_ONLY, settings.requirePasswordForExitOnly)
            .putInt(
                KEY_ADMIN_UNLOCK_TIMEOUT_MINUTES,
                clampInt(
                    settings.adminUnlockTimeoutMinutes,
                    MIN_ADMIN_UNLOCK_TIMEOUT_MINUTES,
                    MAX_ADMIN_UNLOCK_TIMEOUT_MINUTES
                )
            )
            .putBoolean(KEY_SCHEDULE_PROFILES_ENABLED, settings.scheduleProfilesEnabled)
            .putInt(KEY_HOME_START_HOUR, clampInt(settings.homeStartHour, MIN_HOUR, MAX_HOUR))
            .putInt(KEY_WALL_START_HOUR, clampInt(settings.wallStartHour, MIN_HOUR, MAX_HOUR))
            .putInt(KEY_NIGHT_START_HOUR, clampInt(settings.nightStartHour, MIN_HOUR, MAX_HOUR))
            .putString(KEY_PROFILE_HOME_PATH, normalizeDashboardPath(settings.profileHomePath))
            .putString(KEY_PROFILE_WALL_PATH, normalizeDashboardPath(settings.profileWallPath))
            .putString(KEY_PROFILE_NIGHT_PATH, normalizeDashboardPath(settings.profileNightPath))
            .putBoolean(KEY_MAINTENANCE_ENABLED, settings.maintenanceEnabled)
            .putInt(KEY_MAINTENANCE_HOUR, clampInt(settings.maintenanceHour, MIN_HOUR, MAX_HOUR))
            .putInt(
                KEY_MAINTENANCE_MINUTE,
                clampInt(settings.maintenanceMinute, MIN_MINUTE, MAX_MINUTE)
            )
            .putBoolean(KEY_AMBIENT_MODE_ENABLED, settings.ambientModeEnabled)
            .putInt(
                KEY_AMBIENT_DIM_AFTER_SECONDS,
                clampInt(
                    settings.ambientDimAfterSeconds,
                    MIN_AMBIENT_DIM_AFTER_SECONDS,
                    MAX_AMBIENT_DIM_AFTER_SECONDS
                )
            )
            .putInt(
                KEY_AMBIENT_BRIGHTNESS_PERCENT,
                clampInt(
                    settings.ambientBrightnessPercent,
                    MIN_AMBIENT_BRIGHTNESS_PERCENT,
                    MAX_AMBIENT_BRIGHTNESS_PERCENT
                )
            )
            .putBoolean(KEY_PRESENCE_WAKE_ENABLED, settings.presenceWakeEnabled)
            .putInt(
                KEY_PRESENCE_WAKE_COOLDOWN_SECONDS,
                clampInt(
                    settings.presenceWakeCooldownSeconds,
                    MIN_PRESENCE_WAKE_COOLDOWN_SECONDS,
                    MAX_PRESENCE_WAKE_COOLDOWN_SECONDS
                )
            )
            .putBoolean(KEY_AUTO_DISCOVER_HOME_ASSISTANT, settings.autoDiscoverHomeAssistant)
            .putBoolean(KEY_LOCAL_CONTROL_ENABLED, settings.localControlEnabled)
            .putInt(
                KEY_LOCAL_CONTROL_PORT,
                clampInt(
                    settings.localControlPort,
                    MIN_LOCAL_CONTROL_PORT,
                    MAX_LOCAL_CONTROL_PORT
                )
            )
            .putBoolean(
                KEY_LOCAL_CONTROL_PREVIEW_ENABLED,
                settings.localControlPreviewEnabled
            )
            .apply()
    }

    fun resetToDefaults(): KioskSettings {
        val defaults = KioskSettings(
            browserEngine = DEFAULT_BROWSER_ENGINE,
            homeAssistantUrl = DEFAULT_HOME_ASSISTANT_URL,
            dashboardPath = DEFAULT_DASHBOARD_PATH,
            appendKiosk = DEFAULT_APPEND_KIOSK,
            reloadIntervalSeconds = DEFAULT_RELOAD_INTERVAL_SECONDS,
            keepScreenOn = DEFAULT_KEEP_SCREEN_ON,
            autoStartOnBoot = DEFAULT_AUTOSTART_ON_BOOT,
            fullscreen = DEFAULT_FULLSCREEN,
            themeMode = DEFAULT_THEME_MODE,
            lockTaskMode = DEFAULT_LOCK_TASK_MODE,
            autoReloadOnFailure = DEFAULT_AUTO_RELOAD_ON_FAILURE,
            watchdogEnabled = DEFAULT_WATCHDOG_ENABLED,
            watchdogPingPath = DEFAULT_WATCHDOG_PING_PATH,
            watchdogPingIntervalSeconds = DEFAULT_WATCHDOG_PING_INTERVAL_SECONDS,
            updatesEnabled = DEFAULT_UPDATES_ENABLED,
            updatesRepo = DEFAULT_UPDATES_REPO,
            updateCheckIntervalHours = DEFAULT_UPDATE_CHECK_INTERVAL_HOURS,
            allowMixedContent = DEFAULT_ALLOW_MIXED_CONTENT,
            allowThirdPartyCookies = DEFAULT_ALLOW_THIRD_PARTY_COOKIES,
            autoplayEnabled = DEFAULT_AUTOPLAY_ENABLED,
            desktopMode = DEFAULT_DESKTOP_MODE,
            customUserAgent = DEFAULT_CUSTOM_USER_AGENT,
            requirePasswordForExitOnly = DEFAULT_REQUIRE_PASSWORD_FOR_EXIT_ONLY,
            adminUnlockTimeoutMinutes = DEFAULT_ADMIN_UNLOCK_TIMEOUT_MINUTES,
            scheduleProfilesEnabled = DEFAULT_SCHEDULE_PROFILES_ENABLED,
            homeStartHour = DEFAULT_HOME_START_HOUR,
            wallStartHour = DEFAULT_WALL_START_HOUR,
            nightStartHour = DEFAULT_NIGHT_START_HOUR,
            profileHomePath = DEFAULT_PROFILE_HOME_PATH,
            profileWallPath = DEFAULT_PROFILE_WALL_PATH,
            profileNightPath = DEFAULT_PROFILE_NIGHT_PATH,
            maintenanceEnabled = DEFAULT_MAINTENANCE_ENABLED,
            maintenanceHour = DEFAULT_MAINTENANCE_HOUR,
            maintenanceMinute = DEFAULT_MAINTENANCE_MINUTE,
            ambientModeEnabled = DEFAULT_AMBIENT_MODE_ENABLED,
            ambientDimAfterSeconds = DEFAULT_AMBIENT_DIM_AFTER_SECONDS,
            ambientBrightnessPercent = DEFAULT_AMBIENT_BRIGHTNESS_PERCENT,
            presenceWakeEnabled = DEFAULT_PRESENCE_WAKE_ENABLED,
            presenceWakeCooldownSeconds = DEFAULT_PRESENCE_WAKE_COOLDOWN_SECONDS,
            autoDiscoverHomeAssistant = DEFAULT_AUTO_DISCOVER_HOME_ASSISTANT,
            localControlEnabled = DEFAULT_LOCAL_CONTROL_ENABLED,
            localControlPort = DEFAULT_LOCAL_CONTROL_PORT,
            localControlPreviewEnabled = DEFAULT_LOCAL_CONTROL_PREVIEW_ENABLED
        )
        save(defaults)
        return defaults
    }

    fun hasAdminPassword(): Boolean {
        return !prefs.getString(KEY_ADMIN_PASSWORD_HASH, "").isNullOrBlank()
    }

    fun verifyAdminPassword(candidate: String): Boolean {
        val stored = prefs.getString(KEY_ADMIN_PASSWORD_HASH, "").orEmpty()
        if (stored.isBlank()) {
            return candidate.isBlank()
        }

        val normalized = candidate.trim()
        return if (stored.startsWith(PASSWORD_HASH_PREFIX)) {
            val matches = verifyPbkdf2Password(stored, normalized)
            if (matches) {
                val iterationCount = parsePbkdf2Iterations(stored)
                if (iterationCount != null && iterationCount > PBKDF2_ITERATIONS) {
                    // Re-save with the current work factor so future unlocks are faster on older tablets.
                    setAdminPassword(normalized)
                }
            }
            matches
        } else {
            val legacyMatches = stored == hashLegacyPassword(normalized)
            if (legacyMatches) {
                setAdminPassword(normalized)
            }
            legacyMatches
        }
    }

    fun setAdminPassword(rawPassword: String) {
        val normalized = rawPassword.trim()
        if (normalized.isEmpty()) {
            clearAdminPassword()
            return
        }
        prefs.edit()
            .putString(KEY_ADMIN_PASSWORD_HASH, createPbkdf2Hash(normalized))
            .apply()
    }

    fun clearAdminPassword() {
        prefs.edit()
            .remove(KEY_ADMIN_PASSWORD_HASH)
            .remove(KEY_LAST_ADMIN_UNLOCK_AT_MILLIS)
            .apply()
    }

    fun markAdminUnlockedNow() {
        prefs.edit().putLong(KEY_LAST_ADMIN_UNLOCK_AT_MILLIS, System.currentTimeMillis()).apply()
    }

    fun isAdminSessionStillValid(settings: KioskSettings = load()): Boolean {
        if (!hasAdminPassword()) {
            return true
        }
        val lastUnlockAt = prefs.getLong(KEY_LAST_ADMIN_UNLOCK_AT_MILLIS, 0L)
        if (lastUnlockAt <= 0L) {
            return false
        }
        val timeoutMs = settings.adminUnlockTimeoutMinutes * 60_000L
        return System.currentTimeMillis() - lastUnlockAt <= timeoutMs
    }

    fun recordLastEngine(engine: BrowserEngine) {
        prefs.edit().putString(KEY_DIAG_LAST_ENGINE, engine.name).apply()
    }

    fun recordLastUrl(url: String) {
        prefs.edit().putString(KEY_DIAG_LAST_URL, url).apply()
    }

    fun recordLastLoadStatus(status: String) {
        prefs.edit().putString(KEY_DIAG_LAST_LOAD_STATUS, status).apply()
    }

    fun recordCrash(reason: String) {
        prefs.edit()
            .putString(KEY_DIAG_LAST_CRASH_REASON, reason)
            .putLong(KEY_DIAG_LAST_CRASH_AT_MILLIS, System.currentTimeMillis())
            .apply()
    }

    fun recordNetworkState(state: String) {
        prefs.edit().putString(KEY_DIAG_LAST_NETWORK_STATE, state).apply()
    }

    fun recordWatchdogState(state: String) {
        prefs.edit().putString(KEY_DIAG_LAST_WATCHDOG_STATE, state).apply()
    }

    fun recordUpdateState(state: String) {
        prefs.edit().putString(KEY_DIAG_LAST_UPDATE_STATE, state).apply()
    }

    fun diagnosticsSnapshot(): DiagnosticsSnapshot {
        return DiagnosticsSnapshot(
            lastEngine = prefs.getString(KEY_DIAG_LAST_ENGINE, "unknown").orEmpty(),
            lastUrl = prefs.getString(KEY_DIAG_LAST_URL, "-").orEmpty(),
            lastLoadStatus = prefs.getString(KEY_DIAG_LAST_LOAD_STATUS, "-").orEmpty(),
            lastCrashReason = prefs.getString(KEY_DIAG_LAST_CRASH_REASON, "-").orEmpty(),
            lastCrashAtMillis = prefs.getLong(KEY_DIAG_LAST_CRASH_AT_MILLIS, 0L),
            lastNetworkState = prefs.getString(KEY_DIAG_LAST_NETWORK_STATE, "unknown").orEmpty(),
            lastWatchdogState = prefs.getString(KEY_DIAG_LAST_WATCHDOG_STATE, "unknown").orEmpty(),
            lastUpdateState = prefs.getString(KEY_DIAG_LAST_UPDATE_STATE, "unknown").orEmpty()
        )
    }

    fun exportSettingsJson(settings: KioskSettings = load()): String {
        val json = JSONObject().apply {
            put("browserEngine", settings.browserEngine.name)
            put("homeAssistantUrl", settings.homeAssistantUrl)
            put("dashboardPath", settings.dashboardPath)
            put("appendKiosk", settings.appendKiosk)
            put("reloadIntervalSeconds", settings.reloadIntervalSeconds)
            put("keepScreenOn", settings.keepScreenOn)
            put("autoStartOnBoot", settings.autoStartOnBoot)
            put("fullscreen", settings.fullscreen)
            put("themeMode", settings.themeMode.name)
            put("lockTaskMode", settings.lockTaskMode)
            put("autoReloadOnFailure", settings.autoReloadOnFailure)
            put("watchdogEnabled", settings.watchdogEnabled)
            put("watchdogPingPath", settings.watchdogPingPath)
            put("watchdogPingIntervalSeconds", settings.watchdogPingIntervalSeconds)
            put("updatesEnabled", settings.updatesEnabled)
            put("updatesRepo", settings.updatesRepo)
            put("updateCheckIntervalHours", settings.updateCheckIntervalHours)
            put("allowMixedContent", settings.allowMixedContent)
            put("allowThirdPartyCookies", settings.allowThirdPartyCookies)
            put("autoplayEnabled", settings.autoplayEnabled)
            put("desktopMode", settings.desktopMode)
            put("customUserAgent", settings.customUserAgent)
            put("requirePasswordForExitOnly", settings.requirePasswordForExitOnly)
            put("adminUnlockTimeoutMinutes", settings.adminUnlockTimeoutMinutes)
            put("scheduleProfilesEnabled", settings.scheduleProfilesEnabled)
            put("homeStartHour", settings.homeStartHour)
            put("wallStartHour", settings.wallStartHour)
            put("nightStartHour", settings.nightStartHour)
            put("profileHomePath", settings.profileHomePath)
            put("profileWallPath", settings.profileWallPath)
            put("profileNightPath", settings.profileNightPath)
            put("maintenanceEnabled", settings.maintenanceEnabled)
            put("maintenanceHour", settings.maintenanceHour)
            put("maintenanceMinute", settings.maintenanceMinute)
            put("ambientModeEnabled", settings.ambientModeEnabled)
            put("ambientDimAfterSeconds", settings.ambientDimAfterSeconds)
            put("ambientBrightnessPercent", settings.ambientBrightnessPercent)
            put("presenceWakeEnabled", settings.presenceWakeEnabled)
            put("presenceWakeCooldownSeconds", settings.presenceWakeCooldownSeconds)
            put("autoDiscoverHomeAssistant", settings.autoDiscoverHomeAssistant)
            put("localControlEnabled", settings.localControlEnabled)
            put("localControlPort", settings.localControlPort)
            put("localControlPreviewEnabled", settings.localControlPreviewEnabled)
        }
        return json.toString(2)
    }

    fun importSettingsJson(rawJson: String): KioskSettings {
        val current = load()
        val json = JSONObject(rawJson)
        val merged = current.copy(
            browserEngine = BrowserEngine.fromStoredValue(json.optString("browserEngine")),
            homeAssistantUrl = json.optString("homeAssistantUrl", current.homeAssistantUrl),
            dashboardPath = json.optString("dashboardPath", current.dashboardPath),
            appendKiosk = json.optBoolean("appendKiosk", current.appendKiosk),
            reloadIntervalSeconds = json.optInt("reloadIntervalSeconds", current.reloadIntervalSeconds),
            keepScreenOn = json.optBoolean("keepScreenOn", current.keepScreenOn),
            autoStartOnBoot = json.optBoolean("autoStartOnBoot", current.autoStartOnBoot),
            fullscreen = json.optBoolean("fullscreen", current.fullscreen),
            themeMode = ThemeMode.fromStoredValue(json.optString("themeMode", current.themeMode.name)),
            lockTaskMode = json.optBoolean("lockTaskMode", current.lockTaskMode),
            autoReloadOnFailure = json.optBoolean("autoReloadOnFailure", current.autoReloadOnFailure),
            watchdogEnabled = json.optBoolean("watchdogEnabled", current.watchdogEnabled),
            watchdogPingPath = json.optString("watchdogPingPath", current.watchdogPingPath),
            watchdogPingIntervalSeconds = json.optInt(
                "watchdogPingIntervalSeconds",
                current.watchdogPingIntervalSeconds
            ),
            updatesEnabled = json.optBoolean("updatesEnabled", current.updatesEnabled),
            updatesRepo = json.optString("updatesRepo", current.updatesRepo),
            updateCheckIntervalHours = json.optInt("updateCheckIntervalHours", current.updateCheckIntervalHours),
            allowMixedContent = json.optBoolean("allowMixedContent", current.allowMixedContent),
            allowThirdPartyCookies = json.optBoolean(
                "allowThirdPartyCookies",
                current.allowThirdPartyCookies
            ),
            autoplayEnabled = json.optBoolean("autoplayEnabled", current.autoplayEnabled),
            desktopMode = json.optBoolean("desktopMode", current.desktopMode),
            customUserAgent = json.optString("customUserAgent", current.customUserAgent),
            requirePasswordForExitOnly = json.optBoolean(
                "requirePasswordForExitOnly",
                current.requirePasswordForExitOnly
            ),
            adminUnlockTimeoutMinutes = json.optInt(
                "adminUnlockTimeoutMinutes",
                current.adminUnlockTimeoutMinutes
            ),
            scheduleProfilesEnabled = json.optBoolean(
                "scheduleProfilesEnabled",
                current.scheduleProfilesEnabled
            ),
            homeStartHour = json.optInt("homeStartHour", current.homeStartHour),
            wallStartHour = json.optInt("wallStartHour", current.wallStartHour),
            nightStartHour = json.optInt("nightStartHour", current.nightStartHour),
            profileHomePath = json.optString("profileHomePath", current.profileHomePath),
            profileWallPath = json.optString("profileWallPath", current.profileWallPath),
            profileNightPath = json.optString("profileNightPath", current.profileNightPath),
            maintenanceEnabled = json.optBoolean("maintenanceEnabled", current.maintenanceEnabled),
            maintenanceHour = json.optInt("maintenanceHour", current.maintenanceHour),
            maintenanceMinute = json.optInt("maintenanceMinute", current.maintenanceMinute),
            ambientModeEnabled = json.optBoolean("ambientModeEnabled", current.ambientModeEnabled),
            ambientDimAfterSeconds = json.optInt("ambientDimAfterSeconds", current.ambientDimAfterSeconds),
            ambientBrightnessPercent = json.optInt(
                "ambientBrightnessPercent",
                current.ambientBrightnessPercent
            ),
            presenceWakeEnabled = json.optBoolean("presenceWakeEnabled", current.presenceWakeEnabled),
            presenceWakeCooldownSeconds = json.optInt(
                "presenceWakeCooldownSeconds",
                current.presenceWakeCooldownSeconds
            ),
            autoDiscoverHomeAssistant = json.optBoolean(
                "autoDiscoverHomeAssistant",
                current.autoDiscoverHomeAssistant
            ),
            localControlEnabled = json.optBoolean(
                "localControlEnabled",
                current.localControlEnabled
            ),
            localControlPort = json.optInt(
                "localControlPort",
                current.localControlPort
            ),
            localControlPreviewEnabled = json.optBoolean(
                "localControlPreviewEnabled",
                current.localControlPreviewEnabled
            )
        )
        save(merged)
        return load()
    }

    fun buildDashboardUrl(settings: KioskSettings = load()): String {
        val normalizedBase = normalizeBaseUrl(settings.homeAssistantUrl)
        val base = if (isHttpOrHttpsUrl(normalizedBase)) {
            normalizedBase
        } else {
            DEFAULT_HOME_ASSISTANT_URL
        }.trimEnd('/')
        val path = normalizeDashboardPath(settings.dashboardPath)

        var url = if (path.isBlank()) {
            base
        } else {
            "$base/$path"
        }

        if (settings.appendKiosk && !KIOSK_QUERY_REGEX.containsMatchIn(url)) {
            url += if (url.contains("?")) "&kiosk" else "?kiosk"
        }
        return url
    }

    fun applyThemeMode() {
        AppCompatDelegate.setDefaultNightMode(load().themeMode.appCompatMode)
    }

    fun shouldUseDesktopUa(settings: KioskSettings = load()): Boolean {
        return settings.desktopMode
    }

    companion object {
        private const val PREF_FILE = "ha_kiosk_prefs"

        private const val KEY_BROWSER_ENGINE = "browser_engine"
        private const val KEY_ADMIN_PASSWORD_HASH = "admin_password_hash"
        private const val KEY_HOME_ASSISTANT_URL = "home_assistant_url"
        private const val KEY_DASHBOARD_PATH = "dashboard_path"
        private const val KEY_APPEND_KIOSK = "append_kiosk"
        private const val KEY_RELOAD_INTERVAL_SECONDS = "reload_interval_seconds"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_AUTOSTART_ON_BOOT = "autostart_on_boot"
        private const val KEY_FULLSCREEN = "fullscreen"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LOCK_TASK_MODE = "lock_task_mode"
        private const val KEY_AUTO_RELOAD_ON_FAILURE = "auto_reload_on_failure"
        private const val KEY_WATCHDOG_ENABLED = "watchdog_enabled"
        private const val KEY_WATCHDOG_PING_PATH = "watchdog_ping_path"
        private const val KEY_WATCHDOG_PING_INTERVAL_SECONDS = "watchdog_ping_interval_seconds"
        private const val KEY_UPDATES_ENABLED = "updates_enabled"
        private const val KEY_UPDATES_REPO = "updates_repo"
        private const val KEY_UPDATE_CHECK_INTERVAL_HOURS = "update_check_interval_hours"
        private const val KEY_ALLOW_MIXED_CONTENT = "allow_mixed_content"
        private const val KEY_ALLOW_THIRD_PARTY_COOKIES = "allow_third_party_cookies"
        private const val KEY_AUTOPLAY_ENABLED = "autoplay_enabled"
        private const val KEY_DESKTOP_MODE = "desktop_mode"
        private const val KEY_CUSTOM_USER_AGENT = "custom_user_agent"
        private const val KEY_REQUIRE_PASSWORD_FOR_EXIT_ONLY = "require_password_for_exit_only"
        private const val KEY_ADMIN_UNLOCK_TIMEOUT_MINUTES = "admin_unlock_timeout_minutes"
        private const val KEY_LAST_ADMIN_UNLOCK_AT_MILLIS = "last_admin_unlock_at_millis"
        private const val KEY_SCHEDULE_PROFILES_ENABLED = "schedule_profiles_enabled"
        private const val KEY_HOME_START_HOUR = "home_start_hour"
        private const val KEY_WALL_START_HOUR = "wall_start_hour"
        private const val KEY_NIGHT_START_HOUR = "night_start_hour"
        private const val KEY_PROFILE_HOME_PATH = "profile_home_path"
        private const val KEY_PROFILE_WALL_PATH = "profile_wall_path"
        private const val KEY_PROFILE_NIGHT_PATH = "profile_night_path"
        private const val KEY_MAINTENANCE_ENABLED = "maintenance_enabled"
        private const val KEY_MAINTENANCE_HOUR = "maintenance_hour"
        private const val KEY_MAINTENANCE_MINUTE = "maintenance_minute"
        private const val KEY_AMBIENT_MODE_ENABLED = "ambient_mode_enabled"
        private const val KEY_AMBIENT_DIM_AFTER_SECONDS = "ambient_dim_after_seconds"
        private const val KEY_AMBIENT_BRIGHTNESS_PERCENT = "ambient_brightness_percent"
        private const val KEY_PRESENCE_WAKE_ENABLED = "presence_wake_enabled"
        private const val KEY_PRESENCE_WAKE_COOLDOWN_SECONDS = "presence_wake_cooldown_seconds"
        private const val KEY_AUTO_DISCOVER_HOME_ASSISTANT = "auto_discover_home_assistant"
        private const val KEY_LOCAL_CONTROL_ENABLED = "local_control_enabled"
        private const val KEY_LOCAL_CONTROL_PORT = "local_control_port"
        private const val KEY_LOCAL_CONTROL_PREVIEW_ENABLED = "local_control_preview_enabled"
        private const val KEY_STARTUP_CAMERA_PROMPT_SHOWN = "startup_camera_prompt_shown"

        private const val KEY_DIAG_LAST_ENGINE = "diag_last_engine"
        private const val KEY_DIAG_LAST_URL = "diag_last_url"
        private const val KEY_DIAG_LAST_LOAD_STATUS = "diag_last_load_status"
        private const val KEY_DIAG_LAST_CRASH_REASON = "diag_last_crash_reason"
        private const val KEY_DIAG_LAST_CRASH_AT_MILLIS = "diag_last_crash_at_millis"
        private const val KEY_DIAG_LAST_NETWORK_STATE = "diag_last_network_state"
        private const val KEY_DIAG_LAST_WATCHDOG_STATE = "diag_last_watchdog_state"
        private const val KEY_DIAG_LAST_UPDATE_STATE = "diag_last_update_state"

        private val KIOSK_QUERY_REGEX = Regex("([?&])kiosk(=|&|$)")
        private const val PASSWORD_HASH_PREFIX = "pbkdf2_sha256"
        private const val PBKDF2_ITERATIONS = 120_000
        private const val PBKDF2_KEY_LENGTH_BITS = 256
        private const val PBKDF2_SALT_BYTES = 16

        val DEFAULT_BROWSER_ENGINE = BrowserEngine.GECKO
        const val DEFAULT_HOME_ASSISTANT_URL = "https://exraaaa.github.io"
        const val DEFAULT_DASHBOARD_PATH = "KioskZen"
        const val DEFAULT_APPEND_KIOSK = false
        const val DEFAULT_RELOAD_INTERVAL_SECONDS = 20
        const val DEFAULT_KEEP_SCREEN_ON = true
        const val DEFAULT_AUTOSTART_ON_BOOT = true
        const val DEFAULT_FULLSCREEN = true
        val DEFAULT_THEME_MODE = ThemeMode.SYSTEM
        const val DEFAULT_LOCK_TASK_MODE = false
        const val DEFAULT_AUTO_RELOAD_ON_FAILURE = true
        const val DEFAULT_WATCHDOG_ENABLED = true
        const val DEFAULT_WATCHDOG_PING_PATH = "api/"
        const val DEFAULT_WATCHDOG_PING_INTERVAL_SECONDS = 45
        const val DEFAULT_UPDATES_ENABLED = false
        const val DEFAULT_UPDATES_REPO = "exraaaa/KioskZen"
        private const val LEGACY_UPDATES_REPO = "alexrafaelhaidu/KioskZen"
        const val DEFAULT_UPDATE_CHECK_INTERVAL_HOURS = 12
        const val DEFAULT_ALLOW_MIXED_CONTENT = true
        const val DEFAULT_ALLOW_THIRD_PARTY_COOKIES = false
        const val DEFAULT_AUTOPLAY_ENABLED = true
        const val DEFAULT_DESKTOP_MODE = false
        const val DEFAULT_CUSTOM_USER_AGENT = ""
        const val DEFAULT_REQUIRE_PASSWORD_FOR_EXIT_ONLY = false
        const val DEFAULT_ADMIN_UNLOCK_TIMEOUT_MINUTES = 15
        const val DEFAULT_SCHEDULE_PROFILES_ENABLED = false
        const val DEFAULT_HOME_START_HOUR = 6
        const val DEFAULT_WALL_START_HOUR = 10
        const val DEFAULT_NIGHT_START_HOUR = 21
        const val DEFAULT_PROFILE_HOME_PATH = DEFAULT_DASHBOARD_PATH
        const val DEFAULT_PROFILE_WALL_PATH = DEFAULT_DASHBOARD_PATH
        const val DEFAULT_PROFILE_NIGHT_PATH = DEFAULT_DASHBOARD_PATH
        const val DEFAULT_MAINTENANCE_ENABLED = false
        const val DEFAULT_MAINTENANCE_HOUR = 3
        const val DEFAULT_MAINTENANCE_MINUTE = 0
        const val DEFAULT_AMBIENT_MODE_ENABLED = false
        const val DEFAULT_AMBIENT_DIM_AFTER_SECONDS = 120
        const val DEFAULT_AMBIENT_BRIGHTNESS_PERCENT = 25
        const val DEFAULT_PRESENCE_WAKE_ENABLED = false
        const val DEFAULT_PRESENCE_WAKE_COOLDOWN_SECONDS = 8
        const val DEFAULT_AUTO_DISCOVER_HOME_ASSISTANT = false
        const val DEFAULT_LOCAL_CONTROL_ENABLED = true
        const val DEFAULT_LOCAL_CONTROL_PORT = 8099
        const val DEFAULT_LOCAL_CONTROL_PREVIEW_ENABLED = false

        const val MIN_RELOAD_INTERVAL_SECONDS = 5
        const val MAX_RELOAD_INTERVAL_SECONDS = 600
        const val MIN_WATCHDOG_PING_INTERVAL_SECONDS = 15
        const val MAX_WATCHDOG_PING_INTERVAL_SECONDS = 1800
        const val MIN_UPDATE_CHECK_INTERVAL_HOURS = 1
        const val MAX_UPDATE_CHECK_INTERVAL_HOURS = 168
        const val MIN_ADMIN_UNLOCK_TIMEOUT_MINUTES = 1
        const val MAX_ADMIN_UNLOCK_TIMEOUT_MINUTES = 240
        const val MIN_HOUR = 0
        const val MAX_HOUR = 23
        const val MIN_MINUTE = 0
        const val MAX_MINUTE = 59
        const val MIN_AMBIENT_DIM_AFTER_SECONDS = 15
        const val MAX_AMBIENT_DIM_AFTER_SECONDS = 3600
        const val MIN_AMBIENT_BRIGHTNESS_PERCENT = 5
        const val MAX_AMBIENT_BRIGHTNESS_PERCENT = 100
        const val MIN_PRESENCE_WAKE_COOLDOWN_SECONDS = 2
        const val MAX_PRESENCE_WAKE_COOLDOWN_SECONDS = 60
        const val MIN_LOCAL_CONTROL_PORT = 1024
        const val MAX_LOCAL_CONTROL_PORT = 65535

        fun normalizeBaseUrl(value: String): String {
            val trimmed = value.trim().ifBlank { DEFAULT_HOME_ASSISTANT_URL }
            val withScheme = if (
                trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "http://$trimmed"
            }
            return try {
                Uri.parse(withScheme).normalizeScheme().toString()
            } catch (_: Exception) {
                withScheme
            }
        }

        fun normalizeDashboardPath(value: String): String {
            return value.trim().trimStart('/')
        }

        fun isHttpOrHttpsUrl(url: String): Boolean {
            return try {
                val uri = Uri.parse(url)
                val scheme = uri.scheme?.lowercase()
                val host = uri.host
                (scheme == "http" || scheme == "https") && !host.isNullOrBlank()
            } catch (_: Exception) {
                false
            }
        }

        fun isValidRepoSlug(value: String): Boolean {
            return Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(value.trim())
        }

        private fun migrateRepoSlug(value: String): String {
            val normalized = value.trim()
            return if (normalized.equals(LEGACY_UPDATES_REPO, ignoreCase = true)) {
                DEFAULT_UPDATES_REPO
            } else {
                normalized
            }
        }

        fun clampInt(value: Int, minValue: Int, maxValue: Int): Int {
            return value.coerceIn(minValue, maxValue)
        }

        fun parseSettingsJson(rawJson: String): Result<JSONObject> {
            return runCatching { JSONObject(rawJson) }
        }

        private fun createPbkdf2Hash(rawPassword: String): String {
            val salt = ByteArray(PBKDF2_SALT_BYTES).also { SecureRandom().nextBytes(it) }
            val derived = pbkdf2(rawPassword, salt, PBKDF2_ITERATIONS)
            val encodedSalt = Base64.encodeToString(salt, Base64.NO_WRAP)
            val encodedHash = Base64.encodeToString(derived, Base64.NO_WRAP)
            return "$PASSWORD_HASH_PREFIX:$PBKDF2_ITERATIONS:$encodedSalt:$encodedHash"
        }

        private fun verifyPbkdf2Password(stored: String, rawPassword: String): Boolean {
            val parts = stored.split(':')
            if (parts.size != 4 || parts[0] != PASSWORD_HASH_PREFIX) {
                return false
            }

            val iterations = parts[1].toIntOrNull() ?: return false
            val salt = runCatching { Base64.decode(parts[2], Base64.DEFAULT) }.getOrNull()
                ?: return false
            val expected = runCatching { Base64.decode(parts[3], Base64.DEFAULT) }.getOrNull()
                ?: return false

            val candidate = pbkdf2(rawPassword, salt, iterations)
            return MessageDigest.isEqual(candidate, expected)
        }

        private fun parsePbkdf2Iterations(stored: String): Int? {
            val parts = stored.split(':')
            if (parts.size != 4 || parts[0] != PASSWORD_HASH_PREFIX) {
                return null
            }
            return parts[1].toIntOrNull()
        }

        private fun pbkdf2(rawPassword: String, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(rawPassword.toCharArray(), salt, iterations, PBKDF2_KEY_LENGTH_BITS)
            return try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        private fun hashLegacyPassword(raw: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(raw.toByteArray(Charsets.UTF_8))
            return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}

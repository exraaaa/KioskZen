package com.zenpanel.kiosk

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import kotlin.math.max

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

data class KioskSettings(
    val browserEngine: BrowserEngine,
    val homeAssistantUrl: String,
    val dashboardPath: String,
    val appendKiosk: Boolean,
    val reloadIntervalSeconds: Int,
    val keepScreenOn: Boolean,
    val autoStartOnBoot: Boolean,
    val fullscreen: Boolean
)

class KioskPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

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
            reloadIntervalSeconds = max(
                MIN_RELOAD_INTERVAL_SECONDS,
                prefs.getInt(KEY_RELOAD_INTERVAL_SECONDS, DEFAULT_RELOAD_INTERVAL_SECONDS)
            ),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, DEFAULT_KEEP_SCREEN_ON),
            autoStartOnBoot = prefs.getBoolean(KEY_AUTOSTART_ON_BOOT, DEFAULT_AUTOSTART_ON_BOOT),
            fullscreen = prefs.getBoolean(KEY_FULLSCREEN, DEFAULT_FULLSCREEN)
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
                max(MIN_RELOAD_INTERVAL_SECONDS, settings.reloadIntervalSeconds)
            )
            .putBoolean(KEY_KEEP_SCREEN_ON, settings.keepScreenOn)
            .putBoolean(KEY_AUTOSTART_ON_BOOT, settings.autoStartOnBoot)
            .putBoolean(KEY_FULLSCREEN, settings.fullscreen)
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
            fullscreen = DEFAULT_FULLSCREEN
        )
        save(defaults)
        return defaults
    }

    fun hasAdminPassword(): Boolean {
        return !prefs.getString(KEY_ADMIN_PASSWORD_HASH, "").isNullOrBlank()
    }

    fun verifyAdminPassword(candidate: String): Boolean {
        val stored = prefs.getString(KEY_ADMIN_PASSWORD_HASH, "") ?: ""
        if (stored.isBlank()) {
            return candidate.isBlank()
        }
        return stored == hashPassword(candidate)
    }

    fun setAdminPassword(rawPassword: String) {
        val normalized = rawPassword.trim()
        if (normalized.isEmpty()) {
            clearAdminPassword()
            return
        }
        prefs.edit()
            .putString(KEY_ADMIN_PASSWORD_HASH, hashPassword(normalized))
            .apply()
    }

    fun clearAdminPassword() {
        prefs.edit()
            .remove(KEY_ADMIN_PASSWORD_HASH)
            .apply()
    }

    fun buildDashboardUrl(settings: KioskSettings = load()): String {
        val base = normalizeBaseUrl(settings.homeAssistantUrl).trimEnd('/')
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

        private val KIOSK_QUERY_REGEX = Regex("([?&])kiosk(=|&|$)")

        val DEFAULT_BROWSER_ENGINE = BrowserEngine.GECKO
        const val DEFAULT_HOME_ASSISTANT_URL = "http://192.168.0.231:8123"
        const val DEFAULT_DASHBOARD_PATH = "dashboard-tablet/panel"
        const val DEFAULT_APPEND_KIOSK = true
        const val DEFAULT_RELOAD_INTERVAL_SECONDS = 20
        const val DEFAULT_KEEP_SCREEN_ON = true
        const val DEFAULT_AUTOSTART_ON_BOOT = true
        const val DEFAULT_FULLSCREEN = true
        const val MIN_RELOAD_INTERVAL_SECONDS = 5

        fun normalizeBaseUrl(value: String): String {
            val trimmed = value.trim().ifBlank { DEFAULT_HOME_ASSISTANT_URL }
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "http://$trimmed"
            }
        }

        fun normalizeDashboardPath(value: String): String {
            return value.trim().trimStart('/')
        }

        private fun hashPassword(raw: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(raw.toByteArray(Charsets.UTF_8))
            return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}

package com.zenpanel.kiosk

import android.util.Base64
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Collections
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap

data class LocalControlStatus(
    val appVersion: String,
    val engine: String,
    val dashboardUrl: String,
    val homeAssistantUrl: String,
    val dashboardPath: String,
    val appendKiosk: Boolean,
    val reloadIntervalSeconds: Int,
    val autoReloadOnFailure: Boolean,
    val fullscreen: Boolean,
    val keepScreenOn: Boolean,
    val diagnostics: DiagnosticsSnapshot
)

interface LocalControlCallbacks {
    fun status(): LocalControlStatus
    fun reloadDashboard()
    fun restartEngine()
    fun openSettings()
    fun openUrl(url: String)
    fun saveDashboardSettings(
        homeAssistantUrl: String,
        dashboardPath: String,
        appendKiosk: Boolean,
        reloadIntervalSeconds: Int,
        autoReloadOnFailure: Boolean
    ): Result<Unit>
    fun discoverHomeAssistantServers(): Result<List<HomeAssistantDiscoveryResult>>
    fun connectToHomeAssistantServer(baseUrl: String): Result<Unit>
    fun applyDisplayFlags(fullscreen: Boolean, keepScreenOn: Boolean)
}

class LocalControlServer(
    private val prefs: KioskPreferences,
    private val callbacks: LocalControlCallbacks,
    private val controlPort: Int = DEFAULT_PORT
) : NanoHTTPD(controlPort) {

    private val sessionExpiries = ConcurrentHashMap<String, Long>()
    private val secureRandom = SecureRandom()

    fun startSafely(): Result<Unit> = runCatching {
        start(SOCKET_READ_TIMEOUT, false)
        Log.i(TAG, "Local control server started on port ${activePort()}")
    }

    fun stopSafely() {
        runCatching { stop() }
        sessionExpiries.clear()
    }

    fun activePort(): Int {
        val listeningPort = runCatching { listeningPort }.getOrDefault(-1)
        return if (listeningPort > 0) listeningPort else controlPort
    }

    fun primaryLocalUrl(): String {
        return discoverPrimaryUrl(activePort())
    }

    override fun serve(session: IHTTPSession): Response {
        cleanupExpiredSessions()
        return runCatching { route(session) }
            .getOrElse { error ->
                Log.w(TAG, "Control server request failed", error)
                textResponse(
                    Response.Status.INTERNAL_ERROR,
                    "Control server error: ${error.message ?: "unknown"}"
                )
            }
    }

    private fun route(session: IHTTPSession): Response {
        val path = session.uri.trim().ifBlank { "/" }
        val method = session.method

        if (!isPrivateClient(session)) {
            return textResponse(Response.Status.FORBIDDEN, "Forbidden")
        }

        if (path == "/health") {
            return textResponse(Response.Status.OK, "ok")
        }

        if (!isAuthenticated(session)) {
            if (path == "/login" && method == Method.POST) {
                return handleLogin(session)
            }
            return loginPage(errorMessage = null)
        }

        return when {
            method == Method.GET && path == "/" -> redirect("/overview")
            method == Method.GET && path == "/overview" -> pageOverview(session)
            method == Method.GET && path == "/actions" -> pageActions(session)
            method == Method.GET && path == "/dashboard" -> pageDashboard(session)
            method == Method.GET && path == "/display" -> pageDisplay(session)
            method == Method.GET && path == "/security" -> pageSecurity(session)
            method == Method.POST && path == "/logout" -> handleLogout()
            method == Method.GET && path == "/api/status" -> statusJson()

            method == Method.POST && path == "/action/reload" -> {
                callbacks.reloadDashboard()
                redirectWithMessage("Dashboard reloaded", safeReturnPath(postParams(session)["return_to"]))
            }

            method == Method.POST && path == "/action/restart-engine" -> {
                callbacks.restartEngine()
                redirectWithMessage("Browser engine restarted", safeReturnPath(postParams(session)["return_to"]))
            }

            method == Method.POST && path == "/action/open-settings" -> {
                callbacks.openSettings()
                redirectWithMessage(
                    "Settings opened on tablet",
                    safeReturnPath(postParams(session)["return_to"])
                )
            }

            method == Method.POST && path == "/action/open-url" -> {
                val params = postParams(session)
                val url = params["url"].orEmpty().trim()
                val returnPath = safeReturnPath(params["return_to"])
                if (!KioskPreferences.isHttpOrHttpsUrl(url)) {
                    redirectWithMessage("Enter a valid http:// or https:// URL", returnPath)
                } else {
                    callbacks.openUrl(url)
                    redirectWithMessage("Loaded URL", returnPath)
                }
            }

            method == Method.POST && path == "/action/dashboard-config" -> {
                val params = postParams(session)
                saveDashboardConfig(params, safeReturnPath(params["return_to"]))
            }

            method == Method.POST && path == "/action/discover-home-assistant" -> {
                val params = postParams(session)
                val returnPath = safeReturnPath(params["return_to"])
                callbacks.discoverHomeAssistantServers().fold(
                    onSuccess = { servers ->
                        when {
                            servers.isEmpty() -> {
                                redirectWithMessage(
                                    "No Home Assistant server found on local network",
                                    returnPath
                                )
                            }
                            servers.size == 1 -> {
                                val chosen = servers.first().baseUrl
                                callbacks.connectToHomeAssistantServer(chosen).fold(
                                    onSuccess = {
                                        redirectWithMessage("Connected to $chosen", returnPath)
                                    },
                                    onFailure = { error ->
                                        redirectWithMessage(
                                            error.message ?: "Failed to connect to $chosen",
                                            returnPath
                                        )
                                    }
                                )
                            }
                            else -> {
                                pageDiscoveredServers(
                                    session = session,
                                    candidates = servers,
                                    returnPath = returnPath
                                )
                            }
                        }
                    },
                    onFailure = { error ->
                        redirectWithMessage(
                            error.message ?: "No Home Assistant server found on local network",
                            returnPath
                        )
                    }
                )
            }

            method == Method.POST && path == "/action/select-home-assistant" -> {
                val params = postParams(session)
                val returnPath = safeReturnPath(params["return_to"])
                val selected = params["server_url"].orEmpty().trim()
                if (!KioskPreferences.isHttpOrHttpsUrl(selected)) {
                    redirectWithMessage("Select a valid server URL", returnPath)
                } else {
                    callbacks.connectToHomeAssistantServer(selected).fold(
                        onSuccess = {
                            redirectWithMessage("Connected to $selected", returnPath)
                        },
                        onFailure = { error ->
                            redirectWithMessage(
                                error.message ?: "Failed to connect to $selected",
                                returnPath
                            )
                        }
                    )
                }
            }

            method == Method.POST && path == "/action/display" -> {
                val params = postParams(session)
                val fullscreen = params["fullscreen"] == "on"
                val keepAwake = params["keep_screen_on"] == "on"
                callbacks.applyDisplayFlags(fullscreen, keepAwake)
                redirectWithMessage("Display settings applied", safeReturnPath(params["return_to"]))
            }

            method == Method.POST && path == "/action/change-password" -> {
                val params = postParams(session)
                changePassword(params, safeReturnPath(params["return_to"]))
            }

            else -> textResponse(Response.Status.NOT_FOUND, "Not found")
        }
    }

    private fun pageOverview(session: IHTTPSession): Response {
        val status = callbacks.status()
        val body = """
            <div class="card">
              <h2>System Overview</h2>
              <dl class="status">
                <dt>App version</dt><dd>${escapeHtml(status.appVersion)}</dd>
                <dt>Engine</dt><dd>${escapeHtml(status.engine)}</dd>
                <dt>Dashboard URL</dt><dd>${escapeHtml(status.dashboardUrl)}</dd>
                <dt>Load status</dt><dd>${escapeHtml(status.diagnostics.lastLoadStatus)}</dd>
                <dt>Network</dt><dd>${escapeHtml(status.diagnostics.lastNetworkState)}</dd>
                <dt>Watchdog</dt><dd>${escapeHtml(status.diagnostics.lastWatchdogState)}</dd>
                <dt>Updates</dt><dd>${escapeHtml(status.diagnostics.lastUpdateState)}</dd>
              </dl>
              <div class="row" style="margin-top:10px">
                <a class="btn" href="/api/status" target="_blank" rel="noreferrer">Open JSON status</a>
                <a class="btn" href="/overview">Refresh page</a>
              </div>
            </div>
        """.trimIndent()
        return renderPage(
            title = "Overview",
            activePath = "/overview",
            message = session.parms["msg"],
            body = body,
            session = session
        )
    }

    private fun pageActions(session: IHTTPSession): Response {
        val body = """
            <div class="card">
              <h2>Quick Actions</h2>
              <div class="tile-grid">
                <form method="post" action="/action/reload" class="stack">
                  ${returnInput("/actions")}
                  <button class="btn primary" type="submit">Reload Dashboard</button>
                </form>
                <form method="post" action="/action/restart-engine" class="stack">
                  ${returnInput("/actions")}
                  <button class="btn" type="submit">Restart Browser Engine</button>
                </form>
                <form method="post" action="/action/open-settings" class="stack">
                  ${returnInput("/actions")}
                  <button class="btn" type="submit">Open Settings on Tablet</button>
                </form>
                <form method="post" action="/logout" class="stack">
                  <button class="btn" type="submit">Sign Out</button>
                </form>
              </div>
            </div>
            <div class="card">
              <h2>Open URL Now</h2>
              <form method="post" action="/action/open-url" class="stack">
                ${returnInput("/actions")}
                <input class="field" type="url" name="url" placeholder="https://homeassistant.local/lovelace" />
                <button class="btn primary" type="submit">Open URL on Tablet</button>
              </form>
            </div>
        """.trimIndent()
        return renderPage(
            title = "Actions",
            activePath = "/actions",
            message = session.parms["msg"],
            body = body,
            session = session
        )
    }

    private fun pageDashboard(session: IHTTPSession): Response {
        val status = callbacks.status()
        val body = """
            <div class="card">
              <h2>Dashboard Defaults</h2>
              <form method="post" action="/action/dashboard-config" class="stack">
                ${returnInput("/dashboard")}
                <label class="label">Home Assistant URL</label>
                <input class="field" type="url" name="home_assistant_url" value="${escapeHtml(status.homeAssistantUrl)}" />
                <label class="label">Dashboard path</label>
                <input class="field" type="text" name="dashboard_path" value="${escapeHtml(status.dashboardPath)}" />
                <div class="row">
                  <label class="check">
                    <input type="checkbox" name="append_kiosk" ${if (status.appendKiosk) "checked" else ""} />
                    Append <code>?kiosk</code>
                  </label>
                  <label class="check">
                    <input type="checkbox" name="auto_reload" ${if (status.autoReloadOnFailure) "checked" else ""} />
                    Auto-reload on failure
                  </label>
                </div>
                <label class="label">Reload interval (seconds)</label>
                <input class="field" type="number" min="${KioskPreferences.MIN_RELOAD_INTERVAL_SECONDS}" max="${KioskPreferences.MAX_RELOAD_INTERVAL_SECONDS}" name="reload_interval_seconds" value="${status.reloadIntervalSeconds}" />
                <button class="btn primary" type="submit">Save Dashboard Settings</button>
              </form>
              <form method="post" action="/action/discover-home-assistant" class="stack" style="margin-top:8px">
                ${returnInput("/dashboard")}
                <button class="btn" type="submit">Auto-find and connect Home Assistant</button>
              </form>
            </div>
        """.trimIndent()
        return renderPage(
            title = "Dashboard",
            activePath = "/dashboard",
            message = session.parms["msg"],
            body = body,
            session = session
        )
    }

    private fun pageDisplay(session: IHTTPSession): Response {
        val status = callbacks.status()
        val body = """
            <div class="card">
              <h2>Display Controls</h2>
              <form method="post" action="/action/display" class="stack">
                ${returnInput("/display")}
                <label class="check">
                  <input type="checkbox" name="fullscreen" ${if (status.fullscreen) "checked" else ""} />
                  Fullscreen
                </label>
                <label class="check">
                  <input type="checkbox" name="keep_screen_on" ${if (status.keepScreenOn) "checked" else ""} />
                  Keep screen awake
                </label>
                <button class="btn primary" type="submit">Apply Display Settings</button>
              </form>
            </div>
        """.trimIndent()
        return renderPage(
            title = "Display",
            activePath = "/display",
            message = session.parms["msg"],
            body = body,
            session = session
        )
    }

    private fun pageSecurity(session: IHTTPSession): Response {
        val body = """
            <div class="card">
              <h2>Security</h2>
              <p class="muted">Use the same admin password as the tablet app.</p>
              <form method="post" action="/action/change-password" class="stack">
                ${returnInput("/security")}
                <input class="field" name="current_password" type="password" placeholder="Current password" />
                <input class="field" name="new_password" type="password" placeholder="New password (min 4 chars)" />
                <input class="field" name="confirm_password" type="password" placeholder="Confirm new password" />
                <button class="btn primary" type="submit">Update Admin Password</button>
              </form>
            </div>
        """.trimIndent()
        return renderPage(
            title = "Security",
            activePath = "/security",
            message = session.parms["msg"],
            body = body,
            session = session
        )
    }

    private fun pageDiscoveredServers(
        session: IHTTPSession,
        candidates: List<HomeAssistantDiscoveryResult>,
        returnPath: String
    ): Response {
        val optionsHtml = candidates.mapIndexed { index, candidate ->
            val label = escapeHtml("${candidate.baseUrl} (${candidate.source})")
            val value = escapeHtml(candidate.baseUrl)
            """
                <label class="check">
                  <input type="radio" name="server_url" value="$value" ${if (index == 0) "checked" else ""} />
                  $label
                </label>
            """.trimIndent()
        }.joinToString(separator = "")

        val body = """
            <div class="card">
              <h2>Select Home Assistant Server</h2>
              <form method="post" action="/action/select-home-assistant" class="stack">
                ${returnInput(returnPath)}
                $optionsHtml
                <button class="btn primary" type="submit">Connect Selected Server</button>
              </form>
            </div>
        """.trimIndent()

        return renderPage(
            title = "Dashboard",
            activePath = "/dashboard",
            message = null,
            body = body,
            session = session
        )
    }

    private fun renderPage(
        title: String,
        activePath: String,
        message: String?,
        body: String,
        session: IHTTPSession
    ): Response {
        val hostHeader = session.headers["host"].orEmpty().trim()
        val accessUrl = if (hostHeader.isBlank()) {
            primaryLocalUrl()
        } else {
            "http://$hostHeader"
        }
        val themeCss = buildThemeCss(prefs.load().themeMode)
        val alertHtml = if (message.isNullOrBlank()) {
            ""
        } else {
            """<div class="notice">${escapeHtml(message)}</div>"""
        }

        fun navLink(path: String, label: String): String {
            val activeClass = if (path == activePath) "nav-link active" else "nav-link"
            return """<a class="$activeClass" href="$path">$label</a>"""
        }

        val navHtml = listOf(
            navLink("/overview", "Overview"),
            navLink("/actions", "Actions"),
            navLink("/dashboard", "Dashboard"),
            navLink("/display", "Display"),
            navLink("/security", "Security")
        ).joinToString("")

        val html = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>KioskZen Control - ${escapeHtml(title)}</title>
              <style>
                $themeCss
                * { box-sizing: border-box; }
                body {
                  margin: 0;
                  background: var(--bg);
                  color: var(--text);
                  font: 15px/1.45 "Segoe UI", Roboto, Arial, sans-serif;
                }
                .shell {
                  max-width: 1040px;
                  margin: 0 auto;
                  padding: 12px;
                }
                .topbar {
                  border: 1px solid var(--line);
                  border-radius: 12px;
                  background: var(--panel);
                  box-shadow: 0 4px 12px rgba(16, 35, 68, 0.08);
                  margin-bottom: 10px;
                }
                .title {
                  padding: 11px 12px 8px;
                  border-bottom: 1px solid var(--line);
                }
                .title h1 {
                  margin: 0;
                  font-size: 1.08rem;
                }
                .title p {
                  margin: 4px 0 0;
                  color: var(--muted);
                }
                .nav-wrap {
                  position: relative;
                  overflow-x: auto;
                  padding: 6px;
                }
                .nav {
                  position: relative;
                  display: inline-flex;
                  gap: 6px;
                  min-width: max-content;
                }
                .nav-indicator {
                  position: absolute;
                  top: 2px;
                  left: 0;
                  height: calc(100% - 4px);
                  border-radius: 8px;
                  background: var(--accent);
                  transition: transform 220ms ease, width 220ms ease;
                  z-index: 0;
                }
                .nav-link {
                  position: relative;
                  z-index: 1;
                  color: var(--muted);
                  text-decoration: none;
                  padding: 9px 11px;
                  border-radius: 8px;
                  font-weight: 600;
                  white-space: nowrap;
                  transition: color 180ms ease;
                }
                .nav-link.active {
                  color: #fff;
                }
                .notice {
                  border: 1px solid var(--notice-line);
                  background: var(--notice-bg);
                  border-radius: 10px;
                  color: #205936;
                  padding: 8px 10px;
                  margin-bottom: 10px;
                }
                .card {
                  border: 1px solid var(--line);
                  border-radius: 12px;
                  background: var(--panel);
                  padding: 12px;
                  margin-bottom: 10px;
                }
                .card h2 {
                  margin: 0 0 10px;
                  font-size: 1rem;
                }
                .status {
                  display: grid;
                  grid-template-columns: 170px 1fr;
                  gap: 6px 10px;
                }
                .status dt { color: var(--muted); font-weight: 600; }
                .status dd { margin: 0; word-break: break-word; }
                .muted { color: var(--muted); }
                .stack { display: grid; gap: 8px; }
                .tile-grid {
                  display: grid;
                  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
                  gap: 8px;
                }
                .row {
                  display: flex;
                  flex-wrap: wrap;
                  gap: 8px;
                  align-items: center;
                }
                .btn {
                  display: inline-block;
                  border: 1px solid var(--btn-border);
                  background: var(--btn-bg);
                  color: var(--btn-text);
                  padding: 9px 11px;
                  border-radius: 8px;
                  font: inherit;
                  font-weight: 600;
                  text-decoration: none;
                  cursor: pointer;
                  text-align: center;
                }
                .btn.primary {
                  border-color: var(--accent);
                  background: var(--accent);
                  color: #fff;
                }
                .btn.primary:hover { background: var(--accent-2); }
                .field {
                  width: 100%;
                  border: 1px solid var(--field-border);
                  background: var(--field-bg);
                  color: var(--field-text);
                  border-radius: 8px;
                  padding: 8px 9px;
                  font: inherit;
                }
                .check {
                  display: inline-flex;
                  align-items: center;
                  gap: 7px;
                }
                .label {
                  color: var(--muted);
                  font-size: .92rem;
                  font-weight: 600;
                }
                @media (max-width: 760px) {
                  .status { grid-template-columns: 1fr; }
                }
              </style>
            </head>
            <body>
              <div class="shell">
                <header class="topbar">
                  <div class="title">
                    <h1>KioskZen Browser Control Panel</h1>
                    <p>Connected to: <strong>${escapeHtml(accessUrl)}</strong></p>
                  </div>
                  <div class="nav-wrap">
                    <nav class="nav" id="mainNav">
                      <div class="nav-indicator" id="navIndicator"></div>
                      $navHtml
                    </nav>
                  </div>
                </header>
                $alertHtml
                $body
              </div>
              <script>
                const nav = document.getElementById('mainNav');
                const indicator = document.getElementById('navIndicator');
                const active = nav.querySelector('.nav-link.active');
                if (active) {
                  const left = active.offsetLeft + 2;
                  const width = active.offsetWidth;
                  indicator.style.transform = 'translateX(' + left + 'px)';
                  indicator.style.width = width + 'px';
                }
              </script>
            </body>
            </html>
        """.trimIndent()
        return htmlResponse(html)
    }

    private fun statusJson(): Response {
        val status = callbacks.status()
        val json = JSONObject().apply {
            put("appVersion", status.appVersion)
            put("engine", status.engine)
            put("dashboardUrl", status.dashboardUrl)
            put("homeAssistantUrl", status.homeAssistantUrl)
            put("dashboardPath", status.dashboardPath)
            put("appendKiosk", status.appendKiosk)
            put("reloadIntervalSeconds", status.reloadIntervalSeconds)
            put("autoReloadOnFailure", status.autoReloadOnFailure)
            put("fullscreen", status.fullscreen)
            put("keepScreenOn", status.keepScreenOn)
            put("lastLoadStatus", status.diagnostics.lastLoadStatus)
            put("lastNetworkState", status.diagnostics.lastNetworkState)
            put("lastWatchdogState", status.diagnostics.lastWatchdogState)
            put("lastUpdateState", status.diagnostics.lastUpdateState)
            put("lastCrashReason", status.diagnostics.lastCrashReason)
        }
        val response = newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            json.toString()
        )
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun saveDashboardConfig(params: Map<String, String>, returnPath: String): Response {
        val current = callbacks.status()
        val homeUrl = params["home_assistant_url"].orEmpty().trim().ifBlank {
            current.homeAssistantUrl
        }
        val dashboardPath = params["dashboard_path"].orEmpty().trim()
        val appendKiosk = params["append_kiosk"] == "on"
        val autoReload = params["auto_reload"] == "on"
        val requestedReloadInterval = params["reload_interval_seconds"]
            ?.trim()
            ?.toIntOrNull()
            ?: current.reloadIntervalSeconds

        val clampedReloadInterval = KioskPreferences.clampInt(
            requestedReloadInterval,
            KioskPreferences.MIN_RELOAD_INTERVAL_SECONDS,
            KioskPreferences.MAX_RELOAD_INTERVAL_SECONDS
        )
        val normalizedHome = KioskPreferences.normalizeBaseUrl(homeUrl)
        if (!KioskPreferences.isHttpOrHttpsUrl(normalizedHome)) {
            return redirectWithMessage("Home Assistant URL must be valid http:// or https://", returnPath)
        }

        val saveResult = callbacks.saveDashboardSettings(
            homeAssistantUrl = normalizedHome,
            dashboardPath = KioskPreferences.normalizeDashboardPath(dashboardPath),
            appendKiosk = appendKiosk,
            reloadIntervalSeconds = clampedReloadInterval,
            autoReloadOnFailure = autoReload
        )

        return saveResult.fold(
            onSuccess = {
                callbacks.reloadDashboard()
                redirectWithMessage("Dashboard settings saved", returnPath)
            },
            onFailure = { error ->
                redirectWithMessage("Save failed: ${error.message ?: "unknown"}", returnPath)
            }
        )
    }

    private fun changePassword(params: Map<String, String>, returnPath: String): Response {
        val current = params["current_password"].orEmpty()
        val next = params["new_password"].orEmpty().trim()
        val confirm = params["confirm_password"].orEmpty().trim()

        if (next.length < MIN_PASSWORD_LENGTH) {
            return redirectWithMessage("Use at least $MIN_PASSWORD_LENGTH characters", returnPath)
        }
        if (next != confirm) {
            return redirectWithMessage("New password and confirmation do not match", returnPath)
        }
        if (prefs.hasAdminPassword() && !prefs.verifyAdminPassword(current)) {
            return redirectWithMessage("Current admin password is incorrect", returnPath)
        }

        prefs.setAdminPassword(next)
        return redirectWithMessage("Admin password updated", returnPath)
    }

    private fun loginPage(errorMessage: String?): Response {
        val errorHtml = if (errorMessage.isNullOrBlank()) {
            ""
        } else {
            """<div class="error">${escapeHtml(errorMessage)}</div>"""
        }
        val themeCss = buildThemeCss(prefs.load().themeMode)
        val html = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>KioskZen Login</title>
              <style>
                $themeCss
                body {
                  margin: 0;
                  min-height: 100vh;
                  display: grid;
                  place-items: center;
                  background: var(--bg);
                  color: var(--text);
                  font: 16px/1.45 "Segoe UI", Roboto, Arial, sans-serif;
                }
                .card {
                  width: min(430px, 92vw);
                  border: 1px solid var(--line);
                  border-radius: 12px;
                  background: var(--panel);
                  padding: 16px;
                }
                h1 { margin: 0 0 8px; font-size: 1.25rem; }
                p { margin: 0 0 10px; color: var(--muted); }
                input, button {
                  width: 100%;
                  border-radius: 8px;
                  border: 1px solid var(--field-border);
                  background: var(--field-bg);
                  padding: 10px;
                  font: inherit;
                  color: var(--field-text);
                }
                button {
                  margin-top: 10px;
                  border-color: var(--accent);
                  background: var(--accent);
                  color: #fff;
                  font-weight: 600;
                  cursor: pointer;
                }
                .error {
                  margin-bottom: 10px;
                  border-radius: 8px;
                  border: 1px solid #dbb38f;
                  background: #fff4e5;
                  color: #75511d;
                  padding: 8px 10px;
                }
              </style>
            </head>
            <body>
              <div class="card">
                <h1>KioskZen Control Panel</h1>
                <p>Sign in with the same admin password used in the app.</p>
                $errorHtml
                <form method="post" action="/login">
                  <input type="password" name="password" placeholder="Admin password" autofocus />
                  <button type="submit">Sign In</button>
                </form>
              </div>
            </body>
            </html>
        """.trimIndent()
        return htmlResponse(html)
    }

    private fun handleLogin(session: IHTTPSession): Response {
        if (!prefs.hasAdminPassword()) {
            val token = issueSessionToken()
            val response = redirect("/overview")
            setAuthCookie(response, token)
            return response
        }

        val password = postParams(session)["password"].orEmpty()
        if (prefs.verifyAdminPassword(password)) {
            val token = issueSessionToken()
            val response = redirect("/overview")
            setAuthCookie(response, token)
            return response
        }

        return loginPage("Incorrect admin password")
    }

    private fun handleLogout(): Response {
        val response = redirect("/login")
        response.addHeader(
            "Set-Cookie",
            "$SESSION_COOKIE=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"
        )
        return response
    }

    private fun issueSessionToken(): String {
        val raw = ByteArray(24)
        secureRandom.nextBytes(raw)
        val token = Base64.encodeToString(raw, Base64.NO_WRAP)
            .replace("+", "-")
            .replace("/", "_")
            .replace("=", "")
        sessionExpiries[token] = System.currentTimeMillis() + SESSION_TTL_MILLIS
        return token
    }

    private fun isAuthenticated(session: IHTTPSession): Boolean {
        if (!prefs.hasAdminPassword()) {
            return true
        }
        val token = session.cookies.read(SESSION_COOKIE) ?: return false
        val expiry = sessionExpiries[token] ?: return false
        if (expiry < System.currentTimeMillis()) {
            sessionExpiries.remove(token)
            return false
        }
        sessionExpiries[token] = System.currentTimeMillis() + SESSION_TTL_MILLIS
        return true
    }

    private fun cleanupExpiredSessions() {
        val now = System.currentTimeMillis()
        sessionExpiries.entries.removeIf { it.value < now }
    }

    private fun isPrivateClient(session: IHTTPSession): Boolean {
        val ip = runCatching { session.remoteIpAddress }.getOrNull().orEmpty().trim()
        if (ip.isBlank()) return false
        if (ip == "127.0.0.1" || ip == "::1") return true
        if (ip.startsWith("10.")) return true
        if (ip.startsWith("192.168.")) return true
        if (ip.startsWith("169.254.")) return true
        if (ip.startsWith("172.")) {
            val second = ip.split('.').getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) {
                return true
            }
        }
        return false
    }

    private fun setAuthCookie(response: Response, token: String) {
        response.addHeader(
            "Set-Cookie",
            "$SESSION_COOKIE=$token; Path=/; HttpOnly; SameSite=Lax"
        )
    }

    private fun postParams(session: IHTTPSession): Map<String, String> {
        val files = hashMapOf<String, String>()
        runCatching { session.parseBody(files) }
        return session.parms
    }

    private fun safeReturnPath(candidate: String?): String {
        val value = candidate.orEmpty().trim()
        return if (value in PAGE_PATHS) value else "/overview"
    }

    private fun returnInput(path: String): String {
        return """<input type="hidden" name="return_to" value="$path" />"""
    }

    private fun redirectWithMessage(message: String, targetPath: String): Response {
        val encoded = URLEncoder.encode(message, Charsets.UTF_8.name())
        return redirect("$targetPath?msg=$encoded")
    }

    private fun redirect(location: String): Response {
        val response = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "")
        response.addHeader("Location", location)
        return response
    }

    private fun textResponse(status: Response.IStatus, text: String): Response {
        return newFixedLengthResponse(status, "text/plain; charset=utf-8", text)
    }

    private fun htmlResponse(html: String): Response {
        val response = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun escapeHtml(raw: String): String {
        return raw
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    companion object {
        private const val TAG = "KioskZenControlServer"
        private const val SESSION_COOKIE = "kioskzen_session"
        private const val SESSION_TTL_MILLIS = 30 * 60 * 1_000L
        private const val SOCKET_READ_TIMEOUT = 12_000
        private const val MIN_PASSWORD_LENGTH = 4
        const val DEFAULT_PORT = 8099

        private val PAGE_PATHS = setOf(
            "/overview",
            "/actions",
            "/dashboard",
            "/display",
            "/security"
        )

        private fun buildThemeCss(themeMode: ThemeMode): String {
            val lightTheme = """
                :root {
                  color-scheme: light;
                  --bg: #edf1f7;
                  --panel: #ffffff;
                  --line: #d2dae7;
                  --text: #1f2d42;
                  --muted: #637287;
                  --accent: #2f5cae;
                  --accent-2: #244a8c;
                  --notice-bg: #e8f4ea;
                  --notice-line: #a4cdb0;
                  --btn-bg: #f4f7fc;
                  --btn-border: #b8c4d8;
                  --btn-text: #253750;
                  --field-bg: #ffffff;
                  --field-border: #bfcbdd;
                  --field-text: #1f2d42;
                }
            """.trimIndent()

            val darkTheme = """
                :root {
                  color-scheme: dark;
                  --bg: #0b1422;
                  --panel: #111d30;
                  --line: #26364f;
                  --text: #e6efff;
                  --muted: #9fb2cf;
                  --accent: #6c9dff;
                  --accent-2: #5a8eeb;
                  --notice-bg: #1a3324;
                  --notice-line: #2c5b40;
                  --btn-bg: #192842;
                  --btn-border: #36507a;
                  --btn-text: #dce9ff;
                  --field-bg: #162339;
                  --field-border: #355177;
                  --field-text: #e6efff;
                }
            """.trimIndent()

            return when (themeMode) {
                ThemeMode.LIGHT -> lightTheme
                ThemeMode.DARK -> darkTheme
                ThemeMode.SYSTEM -> """
                    $lightTheme
                    @media (prefers-color-scheme: dark) {
                      :root {
                        color-scheme: dark;
                        --bg: #0b1422;
                        --panel: #111d30;
                        --line: #26364f;
                        --text: #e6efff;
                        --muted: #9fb2cf;
                        --accent: #6c9dff;
                        --accent-2: #5a8eeb;
                        --notice-bg: #1a3324;
                        --notice-line: #2c5b40;
                        --btn-bg: #192842;
                        --btn-border: #36507a;
                        --btn-text: #dce9ff;
                        --field-bg: #162339;
                        --field-border: #355177;
                        --field-text: #e6efff;
                      }
                    }
                """.trimIndent()
            }
        }

        fun discoverLocalUrls(port: Int): List<String> {
            val clampedPort = KioskPreferences.clampInt(
                port,
                KioskPreferences.MIN_LOCAL_CONTROL_PORT,
                KioskPreferences.MAX_LOCAL_CONTROL_PORT
            )
            val urls = mutableListOf<String>()
            urls += "http://127.0.0.1:$clampedPort"
            urls += "http://localhost:$clampedPort"
            val seen = linkedSetOf<String>()
            asList(NetworkInterface.getNetworkInterfaces()).forEach { iface ->
                asList(iface.inetAddresses).forEach { address ->
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val host = address.hostAddress.orEmpty().trim()
                        if (host.isNotBlank()) {
                            seen += "http://$host:$clampedPort"
                        }
                    }
                }
            }
            urls += seen
            return urls.distinct()
        }

        fun discoverPrimaryUrl(port: Int): String {
            val urls = discoverLocalUrls(port)
            val preferred = urls.firstOrNull {
                it.contains("192.168.") || it.contains("10.") || it.contains("172.")
            }
            return preferred ?: urls.firstOrNull() ?: "http://127.0.0.1:$port"
        }

        private fun <T> asList(enumeration: Enumeration<T>?): List<T> {
            if (enumeration == null) return emptyList()
            return Collections.list(enumeration)
        }
    }
}

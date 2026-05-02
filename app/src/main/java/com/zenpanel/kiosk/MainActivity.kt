package com.zenpanel.kiosk

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.zenpanel.kiosk.databinding.ActivityMainBinding
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebRequestError

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: KioskPreferences

    private var runtime: GeckoRuntime? = null
    private var session: GeckoSession? = null
    private var webViewConfigured = false
    private var defaultWebViewUserAgent: String? = null
    private var currentEngine: BrowserEngine? = null
    private var activeSettings: KioskSettings? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRetry: Runnable? = null
    private var pendingPermissionCallback: GeckoSession.PermissionDelegate.Callback? = null
    private var pendingMediaPermissionCallback: GeckoSession.PermissionDelegate.MediaCallback? = null
    private var pendingMediaVideoSources: Array<GeckoSession.PermissionDelegate.MediaSource>? = null
    private var pendingMediaAudioSources: Array<GeckoSession.PermissionDelegate.MediaSource>? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null

    private var tapCount = 0
    private var firstTapAt = 0L
    private var networkWasLost = false
    private var networkCallbackRegistered = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val grantedAll = result.values.all { it }

            pendingPermissionCallback?.let { callback ->
                if (grantedAll) callback.grant() else callback.reject()
            }
            pendingPermissionCallback = null

            pendingWebPermissionRequest?.let { request ->
                if (grantedAll) {
                    request.grant(request.resources)
                } else {
                    request.deny()
                }
            }
            pendingWebPermissionRequest = null

            pendingMediaPermissionCallback?.let { callback ->
                if (grantedAll) {
                    callback.grant(
                        pendingMediaVideoSources?.firstOrNull(),
                        pendingMediaAudioSources?.firstOrNull()
                    )
                } else {
                    callback.reject()
                }
            }
            pendingMediaPermissionCallback = null
            pendingMediaVideoSources = null
            pendingMediaAudioSources = null
        }

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            applyWindowSettings()
            if (result.resultCode == RESULT_OK &&
                result.data?.getBooleanExtra(SettingsActivity.EXTRA_RELOAD_NOW, false) == true
            ) {
                loadDashboard()
            }
        }

    private val connectivityManager: ConnectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            if (networkWasLost) {
                networkWasLost = false
                mainHandler.postDelayed({
                    if (!isDestroyed) {
                        loadDashboard()
                    }
                }, QUICK_RELOAD_DELAY_MS)
            }
        }

        override fun onLost(network: android.net.Network) {
            networkWasLost = true
            scheduleRetry("Network lost")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = KioskPreferences(this)
        WebView.setWebContentsDebuggingEnabled(false)

        setupAdminGesture()
        registerNetworkMonitoring()
        applyWindowSettings()
        loadDashboard()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back is intentionally ignored in kiosk mode.
            }
        })
    }

    override fun onResume() {
        super.onResume()
        applyWindowSettings()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && prefs.load().fullscreen) {
            enterImmersiveMode()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterNetworkMonitoring()
        mainHandler.removeCallbacksAndMessages(null)
        closeGeckoSession()
        destroyWebView()
    }

    private fun setupAdminGesture() {
        binding.adminTapZone.setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - firstTapAt > ADMIN_GESTURE_WINDOW_MS) {
                firstTapAt = now
                tapCount = 1
            } else {
                tapCount += 1
            }

            if (tapCount >= ADMIN_GESTURE_TAP_COUNT) {
                tapCount = 0
                firstTapAt = 0
                requestAdminAccess()
            }
        }
    }

    private fun requestAdminAccess() {
        if (!prefs.hasAdminPassword()) {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_admin_password, null)
        val passwordInput = dialogView.findViewById<TextInputEditText>(R.id.input_admin_unlock_password)
        val passwordLayout =
            dialogView.findViewById<TextInputLayout>(R.id.input_layout_admin_unlock_password)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.enter_admin_password)
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.unlock, null)
            .create()

        dialog.setOnShowListener {
            passwordInput.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val candidate = passwordInput.text?.toString().orEmpty()
                passwordLayout.error = null
                if (prefs.verifyAdminPassword(candidate)) {
                    dialog.dismiss()
                    settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
                } else {
                    passwordLayout.error = getString(R.string.invalid_admin_password)
                    passwordInput.text?.clear()
                }
            }
        }

        dialog.show()
    }

    private fun loadDashboard() {
        pendingRetry?.let(mainHandler::removeCallbacks)
        pendingRetry = null

        val settings = prefs.load()
        activeSettings = settings
        switchEngine(settings.browserEngine)
        val url = prefs.buildDashboardUrl(settings)

        when (settings.browserEngine) {
            BrowserEngine.GECKO -> session?.loadUri(url)
            BrowserEngine.WEBVIEW, BrowserEngine.CHROMIUM_CUSTOM_TAB -> {
                binding.webView.stopLoading()
                applyWebEngineProfile(settings.browserEngine)
                binding.webView.loadUrl(url)
            }
        }
    }

    private fun switchEngine(engine: BrowserEngine) {
        if (currentEngine == engine && (engine != BrowserEngine.GECKO || session != null)) {
            return
        }
        currentEngine = engine

        when (engine) {
            BrowserEngine.GECKO -> {
                binding.geckoView.visibility = View.VISIBLE
                binding.webView.visibility = View.GONE
                createOrRecreateGeckoSession()
            }

            BrowserEngine.WEBVIEW -> {
                closeGeckoSession()
                binding.geckoView.visibility = View.GONE
                binding.webView.visibility = View.VISIBLE
                configureWebViewIfNeeded()
            }

            BrowserEngine.CHROMIUM_CUSTOM_TAB -> {
                closeGeckoSession()
                binding.geckoView.visibility = View.GONE
                binding.webView.visibility = View.VISIBLE
                configureWebViewIfNeeded()
            }
        }
    }

    private fun configureWebViewIfNeeded() {
        if (webViewConfigured) {
            return
        }
        webViewConfigured = true

        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.safeBrowsingEnabled = true

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            defaultWebViewUserAgent = settings.userAgentString

            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread {
                        if (!isTrustedOrigin(request.origin?.toString())) {
                            request.deny()
                            return@runOnUiThread
                        }

                        val requiredPermissions =
                            mapWebResourcesToAndroidPermissions(request.resources)

                        if (requiredPermissions.isEmpty() || hasAllPermissions(requiredPermissions)) {
                            request.grant(request.resources)
                            return@runOnUiThread
                        }

                        pendingWebPermissionRequest?.deny()
                        pendingWebPermissionRequest = request
                        permissionLauncher.launch(requiredPermissions)
                    }
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    if (!request.isForMainFrame) {
                        return false
                    }
                    return if (isAllowedTopLevelUrl(request.url?.toString())) {
                        false
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Blocked navigation to unsupported URL",
                            Toast.LENGTH_SHORT
                        ).show()
                        true
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        scheduleRetry("WebView load error: ${error.description}")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse
                ) {
                    if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                        scheduleRetry("WebView HTTP ${errorResponse.statusCode}")
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: android.webkit.RenderProcessGoneDetail
                ): Boolean {
                    scheduleRetry("WebView render process gone")
                    return true
                }
            }
        }
    }

    private fun applyWebEngineProfile(engine: BrowserEngine) {
        val webSettings = binding.webView.settings
        if (defaultWebViewUserAgent.isNullOrBlank()) {
            defaultWebViewUserAgent = webSettings.userAgentString
        }

        when (engine) {
            BrowserEngine.WEBVIEW -> {
                webSettings.userAgentString = defaultWebViewUserAgent
                webSettings.builtInZoomControls = false
                webSettings.displayZoomControls = false
            }

            BrowserEngine.CHROMIUM_CUSTOM_TAB -> {
                val baseUa = defaultWebViewUserAgent ?: webSettings.userAgentString.orEmpty()
                webSettings.userAgentString = forceChromiumLikeUserAgent(baseUa)
                webSettings.builtInZoomControls = false
                webSettings.displayZoomControls = false
            }

            BrowserEngine.GECKO -> Unit
        }
    }

    private fun forceChromiumLikeUserAgent(base: String): String {
        if (base.isBlank()) {
            return CHROMIUM_FALLBACK_UA
        }
        // Keep the system WebView UA mostly intact, but strip the WebView token.
        return base.replace("; wv", "")
            .replace(" wv", "")
    }

    private fun hasAllPermissions(permissions: Array<String>): Boolean {
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun mapWebResourcesToAndroidPermissions(resources: Array<String>): Array<String> {
        val permissions = linkedSetOf<String>()
        if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
            permissions += Manifest.permission.CAMERA
        }
        if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
            permissions += Manifest.permission.RECORD_AUDIO
        }
        return permissions.toTypedArray()
    }

    private fun isAllowedTopLevelUrl(url: String?): Boolean {
        val scheme = parseUri(url)?.scheme?.lowercase() ?: return false
        return scheme == "http" || scheme == "https"
    }

    private fun isAllowedSubresourceUrl(url: String?): Boolean {
        val scheme = parseUri(url)?.scheme?.lowercase() ?: return false
        return scheme == "http" ||
            scheme == "https" ||
            scheme == "about" ||
            scheme == "data" ||
            scheme == "blob"
    }

    private fun isTrustedOrigin(candidateUrl: String?): Boolean {
        val candidateHost = parseUri(candidateUrl)?.host?.lowercase() ?: return false
        val configuredHost = configuredHomeAssistantHost() ?: return false
        return candidateHost == configuredHost
    }

    private fun configuredHomeAssistantHost(): String? {
        val baseUrl = KioskPreferences.normalizeBaseUrl(
            (activeSettings ?: prefs.load()).homeAssistantUrl
        )
        return parseUri(baseUrl)?.host?.lowercase()
    }

    private fun parseUri(url: String?): Uri? {
        if (url.isNullOrBlank()) {
            return null
        }
        return runCatching { Uri.parse(url) }.getOrNull()
    }

    private fun createOrRecreateGeckoSession() {
        closeGeckoSession()
        session = GeckoSession().also { geckoSession ->
            geckoSession.settings.setSuspendMediaWhenInactive(false)
            geckoSession.permissionDelegate = createGeckoPermissionDelegate()
            geckoSession.navigationDelegate = createGeckoNavigationDelegate()
            geckoSession.progressDelegate = createGeckoProgressDelegate()
            geckoSession.contentDelegate = createGeckoContentDelegate()
            geckoSession.open(getGeckoRuntime())
            binding.geckoView.setSession(geckoSession)
        }
    }

    private fun closeGeckoSession() {
        binding.geckoView.releaseSession()?.close()
        session?.close()
        session = null
    }

    private fun getGeckoRuntime(): GeckoRuntime {
        val current = runtime
        if (current != null) {
            return current
        }
        return GeckoRuntime.create(
            this,
            GeckoRuntimeSettings.Builder()
                .consoleOutput(false)
                .build()
        ).also { runtime = it }
    }

    private fun createGeckoNavigationDelegate(): GeckoSession.NavigationDelegate {
        return object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                return if (isAllowedTopLevelUrl(request.uri)) {
                    GeckoResult.fromValue(AllowOrDeny.ALLOW)
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Blocked navigation to unsupported URL",
                        Toast.LENGTH_SHORT
                    ).show()
                    GeckoResult.fromValue(AllowOrDeny.DENY)
                }
            }

            override fun onSubframeLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                return if (isAllowedSubresourceUrl(request.uri)) {
                    GeckoResult.fromValue(AllowOrDeny.ALLOW)
                } else {
                    GeckoResult.fromValue(AllowOrDeny.DENY)
                }
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                scheduleRetry("Gecko load error")
                return null
            }
        }
    }

    private fun createGeckoProgressDelegate(): GeckoSession.ProgressDelegate {
        return object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (!success) {
                    scheduleRetry("Gecko page finished with failure")
                }
            }
        }
    }

    private fun createGeckoContentDelegate(): GeckoSession.ContentDelegate {
        return object : GeckoSession.ContentDelegate {
            override fun onCrash(session: GeckoSession) {
                recreateGeckoSessionAfterFailure("Gecko content process crashed")
            }

            override fun onKill(session: GeckoSession) {
                recreateGeckoSessionAfterFailure("Gecko content process was killed")
            }
        }
    }

    private fun createGeckoPermissionDelegate(): GeckoSession.PermissionDelegate {
        return object : GeckoSession.PermissionDelegate {
            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<String>?,
                callback: GeckoSession.PermissionDelegate.Callback
            ) {
                if (permissions.isNullOrEmpty()) {
                    callback.grant()
                    return
                }

                if (hasAllPermissions(permissions)) {
                    callback.grant()
                    return
                }

                pendingPermissionCallback = callback
                permissionLauncher.launch(permissions)
            }

            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int> {
                if (!isTrustedOrigin(perm.uri)) {
                    return GeckoResult.fromValue(
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                    )
                }

                val decision = when (perm.permission) {
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
                    GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE,
                    GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS,
                    GeckoSession.PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS,
                    GeckoSession.PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS,
                    GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS ->
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW

                    else -> GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                }
                return GeckoResult.fromValue(decision)
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback
            ) {
                if (!isTrustedOrigin(uri)) {
                    callback.reject()
                    return
                }

                val requiredPermissions = linkedSetOf<String>()
                if (!video.isNullOrEmpty()) {
                    requiredPermissions += Manifest.permission.CAMERA
                }
                if (!audio.isNullOrEmpty()) {
                    requiredPermissions += Manifest.permission.RECORD_AUDIO
                }

                val permissionArray = requiredPermissions.toTypedArray()
                if (permissionArray.isEmpty() || hasAllPermissions(permissionArray)) {
                    callback.grant(video?.firstOrNull(), audio?.firstOrNull())
                    return
                }

                pendingMediaPermissionCallback?.reject()
                pendingMediaPermissionCallback = callback
                pendingMediaVideoSources = video
                pendingMediaAudioSources = audio
                permissionLauncher.launch(permissionArray)
            }
        }
    }

    private fun recreateGeckoSessionAfterFailure(reason: String) {
        Log.w(TAG, reason)
        Toast.makeText(this, "Dashboard engine restarted", Toast.LENGTH_SHORT).show()
        createOrRecreateGeckoSession()
        scheduleRetry(reason)
    }

    private fun destroyWebView() {
        if (!webViewConfigured) {
            return
        }
        binding.webView.apply {
            stopLoading()
            webChromeClient = null
            webViewClient = WebViewClient()
            destroy()
        }
        webViewConfigured = false
    }

    private fun applyWindowSettings() {
        val settings = prefs.load()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        if (settings.fullscreen) {
            enterImmersiveMode()
        } else {
            exitImmersiveMode()
        }
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).show(
            WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars()
        )
    }

    private fun registerNetworkMonitoring() {
        if (networkCallbackRegistered) {
            return
        }
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback", e)
        }
    }

    private fun unregisterNetworkMonitoring() {
        if (!networkCallbackRegistered) {
            return
        }
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
            // Callback may already be unregistered.
        } finally {
            networkCallbackRegistered = false
        }
    }

    private fun scheduleRetry(reason: String) {
        val settings = prefs.load()
        val delayMs = settings.reloadIntervalSeconds * 1_000L

        pendingRetry?.let(mainHandler::removeCallbacks)
        pendingRetry = Runnable { loadDashboard() }
        mainHandler.postDelayed(pendingRetry!!, delayMs)

        Log.w(TAG, "Scheduled reload in ${settings.reloadIntervalSeconds}s. Reason: $reason")
    }

    companion object {
        private const val TAG = "HAKioskMain"
        private const val ADMIN_GESTURE_TAP_COUNT = 5
        private const val ADMIN_GESTURE_WINDOW_MS = 2_500L
        private const val QUICK_RELOAD_DELAY_MS = 1_200L
        private const val CHROMIUM_FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 15; Tablet) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
    }
}

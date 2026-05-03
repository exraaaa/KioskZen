package com.zenpanel.kiosk

import android.Manifest
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
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
import android.view.MotionEvent
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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.zenpanel.kiosk.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebRequestError
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: KioskPreferences

    private var session: GeckoSession? = null
    private var webViewConfigured = false
    private var defaultWebViewUserAgent: String? = null
    private var currentEngine: BrowserEngine? = null
    private var activeSettings: KioskSettings? = null
    private var loadedDashboardUrl: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingRetry: Runnable? = null
    private var ambientDimRunnable: Runnable? = null
    private var pendingPermissionCallback: GeckoSession.PermissionDelegate.Callback? = null
    private var pendingMediaPermissionCallback: GeckoSession.PermissionDelegate.MediaCallback? = null
    private var pendingMediaVideoSources: Array<GeckoSession.PermissionDelegate.MediaSource>? = null
    private var pendingMediaAudioSources: Array<GeckoSession.PermissionDelegate.MediaSource>? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var watchdogJob: Job? = null
    private var maintenanceJob: Job? = null
    private var pendingPresenceWakePermissionRequest = false
    private var pendingStartupCameraPermissionRequest = false
    private var presenceWakeAnalysis: ImageAnalysis? = null
    private var presenceWakeCameraProvider: ProcessCameraProvider? = null
    private var presenceWakeExecutor: ExecutorService? = null

    private var tapCount = 0
    private var firstTapAt = 0L
    private var networkWasLost = false
    private var networkCallbackRegistered = false
    private var watchdogFailureCount = 0
    private var isAmbientDimmed = false
    private var updateCheckInProgress = false
    private var isPresenceWakeFrameInFlight = false
    private var lastPresenceWakeAtMillis = 0L
    private var presenceWakeRequested = false

    private val presenceFaceDetector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        FaceDetection.getClient(options)
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val grantedAll = result.values.all { it }

            pendingPermissionCallback?.let { callback ->
                if (grantedAll) callback.grant() else callback.reject()
            }
            pendingPermissionCallback = null

            pendingWebPermissionRequest?.let { request ->
                if (grantedAll) request.grant(request.resources) else request.deny()
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

            if (pendingStartupCameraPermissionRequest) {
                pendingStartupCameraPermissionRequest = false
                if (result[Manifest.permission.CAMERA] != true) {
                    prefs.recordLastLoadStatus("Startup camera permission denied")
                }
            }

            if (pendingPresenceWakePermissionRequest) {
                pendingPresenceWakePermissionRequest = false
                val cameraGranted = result[Manifest.permission.CAMERA] == true
                if (cameraGranted) {
                    applyPresenceWakePolicy()
                } else {
                    stopPresenceWakePipeline()
                    prefs.recordLastLoadStatus("Presence wake unavailable: camera permission denied")
                }
            }
        }

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            applyWindowSettings()
            applySchedulers()
            if (result.resultCode == RESULT_OK &&
                result.data?.getBooleanExtra(SettingsActivity.EXTRA_EXIT_APP, false) == true
            ) {
                exitKioskNow()
                return@registerForActivityResult
            }

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
                prefs.recordNetworkState("available")
                mainHandler.postDelayed({
                    if (!isDestroyed) {
                        showRecoveryOverlay(getString(R.string.recovery_reconnected), "")
                        loadDashboard()
                    }
                }, QUICK_RELOAD_DELAY_MS)
            }
        }

        override fun onLost(network: android.net.Network) {
            networkWasLost = true
            prefs.recordNetworkState("lost")
            showRecoveryOverlay(getString(R.string.recovery_network_lost), getString(R.string.recovery_waiting))
            scheduleRetry("Network lost")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = KioskPreferences(this)
        prefs.applyThemeMode()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WebView.setWebContentsDebuggingEnabled(false)

        setupAdminGesture()
        registerNetworkMonitoring()
        applyWindowSettings()
        maybePromptStartupCameraPermission()
        applySchedulers()
        loadDashboard()
        maybeRunAutoUpdateCheck()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back is intentionally ignored in kiosk mode.
            }
        })
    }

    override fun onResume() {
        super.onResume()
        prefs.applyThemeMode()
        applyWindowSettings()
        resetAmbientTimer()
        applyPresenceWakePolicy()
    }

    override fun onPause() {
        super.onPause()
        presenceWakeRequested = false
        stopPresenceWakePipeline()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && prefs.load().fullscreen) {
            enterImmersiveMode()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        resetAmbientTimer()
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterNetworkMonitoring()
        presenceWakeRequested = false
        stopPresenceWakePipeline()
        mainHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        closeGeckoSession()
        destroyWebView()
        runCatching { presenceFaceDetector.close() }
        presenceWakeExecutor?.shutdown()
        presenceWakeExecutor = null
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
        val settings = prefs.load()
        val mustPrompt = prefs.hasAdminPassword() &&
            !settings.requirePasswordForExitOnly

        if (!mustPrompt) {
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
            val unlockButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val unlockLabel = getString(R.string.unlock)
            unlockButton.setOnClickListener {
                val candidate = passwordInput.text?.toString().orEmpty()
                passwordLayout.error = null
                if (candidate.isBlank()) {
                    passwordLayout.error = getString(R.string.invalid_admin_password)
                    return@setOnClickListener
                }
                unlockButton.isEnabled = false
                unlockButton.text = getString(R.string.verifying_password)

                scope.launch {
                    val isValid = withContext(Dispatchers.Default) { prefs.verifyAdminPassword(candidate) }
                    if (!dialog.isShowing) return@launch

                    unlockButton.isEnabled = true
                    unlockButton.text = unlockLabel

                    if (isValid) {
                        prefs.markAdminUnlockedNow()
                        dialog.dismiss()
                        settingsLauncher.launch(Intent(this@MainActivity, SettingsActivity::class.java))
                    } else {
                        passwordLayout.error = getString(R.string.invalid_admin_password)
                        passwordInput.text?.clear()
                    }
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
        applyLockTaskMode(settings)
        switchEngine(settings.browserEngine)

        val url = prefs.buildDashboardUrl(settings)
        loadedDashboardUrl = url
        prefs.recordLastUrl(url)
        prefs.recordLastEngine(settings.browserEngine)
        binding.recoveryOverlay.visibility = View.GONE

        when (settings.browserEngine) {
            BrowserEngine.GECKO -> session?.loadUri(url)
            BrowserEngine.WEBVIEW, BrowserEngine.CHROMIUM_CUSTOM_TAB -> {
                binding.webView.stopLoading()
                applyWebEngineProfile(settings)
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

            BrowserEngine.WEBVIEW,
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
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.safeBrowsingEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false

            CookieManager.getInstance().setAcceptCookie(true)
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
                            getString(R.string.blocked_navigation),
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
                        prefs.recordLastLoadStatus("WebView error: ${error.description}")
                        scheduleRetry("WebView load error: ${error.description}")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse
                ) {
                    if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                        prefs.recordLastLoadStatus("WebView HTTP ${errorResponse.statusCode}")
                        scheduleRetry("WebView HTTP ${errorResponse.statusCode}")
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: android.webkit.RenderProcessGoneDetail
                ): Boolean {
                    prefs.recordCrash("WebView render process gone")
                    scheduleRetry("WebView render process gone")
                    return true
                }
            }
        }
    }

    private fun applyWebEngineProfile(settings: KioskSettings) {
        val webSettings = binding.webView.settings
        if (defaultWebViewUserAgent.isNullOrBlank()) {
            defaultWebViewUserAgent = webSettings.userAgentString
        }

        webSettings.mediaPlaybackRequiresUserGesture = !settings.autoplayEnabled
        webSettings.mixedContentMode = if (settings.allowMixedContent) {
            WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        } else {
            WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(
            binding.webView,
            settings.allowThirdPartyCookies
        )

        val defaultUa = defaultWebViewUserAgent ?: webSettings.userAgentString.orEmpty()
        val customUa = settings.customUserAgent.trim()
        webSettings.userAgentString = when {
            customUa.isNotBlank() -> customUa
            settings.desktopMode -> DESKTOP_UA
            settings.browserEngine == BrowserEngine.CHROMIUM_CUSTOM_TAB -> forceChromiumLikeUserAgent(defaultUa)
            else -> defaultUa
        }
    }

    private fun forceChromiumLikeUserAgent(base: String): String {
        if (base.isBlank()) {
            return CHROMIUM_FALLBACK_UA
        }
        return base.replace("; wv", "").replace(" wv", "")
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
        return scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data" || scheme == "blob"
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
        val current = sharedRuntime
        if (current != null) {
            return current
        }
        synchronized(geckoRuntimeLock) {
            val cached = sharedRuntime
            if (cached != null) {
                return cached
            }
            return GeckoRuntime.create(
                this,
                GeckoRuntimeSettings.Builder()
                    .consoleOutput(false)
                    .build()
            ).also { created ->
                sharedRuntime = created
            }
        }
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
                        getString(R.string.blocked_navigation),
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

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (!url.isNullOrBlank()) {
                    prefs.recordLastUrl(url)
                }
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                prefs.recordLastLoadStatus("Gecko load error")
                scheduleRetry("Gecko load error")
                return null
            }
        }
    }

    private fun createGeckoProgressDelegate(): GeckoSession.ProgressDelegate {
        return object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                prefs.recordLastLoadStatus("Loading...")
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                prefs.recordLastLoadStatus(if (success) "Loaded successfully" else "Load failed")
                if (success) {
                    watchdogFailureCount = 0
                    binding.recoveryOverlay.visibility = View.GONE
                } else {
                    scheduleRetry("Gecko page finished with failure")
                }
            }
        }
    }

    private fun createGeckoContentDelegate(): GeckoSession.ContentDelegate {
        return object : GeckoSession.ContentDelegate {
            override fun onCrash(session: GeckoSession) {
                prefs.recordCrash("Gecko content process crashed")
                recreateGeckoSessionAfterFailure("Gecko content process crashed")
            }

            override fun onKill(session: GeckoSession) {
                prefs.recordCrash("Gecko content process was killed")
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
        Toast.makeText(this, getString(R.string.engine_restarted), Toast.LENGTH_SHORT).show()
        showRecoveryOverlay(getString(R.string.recovery_restarting_engine), reason)
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

        applyLockTaskMode(settings)
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

    private fun applyLockTaskMode(settings: KioskSettings) {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, KioskDeviceAdminReceiver::class.java)
        val isDeviceOwner = dpm.isDeviceOwnerApp(packageName)

        if (settings.lockTaskMode) {
            if (isDeviceOwner) {
                runCatching {
                    dpm.setLockTaskPackages(admin, arrayOf(packageName))
                }.onFailure { error ->
                    prefs.recordWatchdogState("lock-task policy failed: ${error.message}")
                }
            }

            val permitted = dpm.isLockTaskPermitted(packageName) || isDeviceOwner
            if (permitted && !isLockTaskModeRunning()) {
                runCatching { startLockTask() }.onFailure { error ->
                    prefs.recordWatchdogState("lock-task start failed: ${error.message}")
                }
            }
        } else {
            attemptStopLockTaskIfRunning()
        }
    }

    private fun attemptStopLockTaskIfRunning() {
        if (!isLockTaskModeRunning()) {
            return
        }
        runCatching { stopLockTask() }.onFailure {
            // Ignore, app may not own lock task in this state.
        }
    }

    private fun isLockTaskModeRunning(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    private fun applySchedulers() {
        scheduleMaintenanceWindow()
        scheduleWatchdog()
        resetAmbientTimer()
        applyPresenceWakePolicy()
    }

    private fun scheduleMaintenanceWindow() {
        maintenanceJob?.cancel()
        val settings = prefs.load()
        if (!settings.maintenanceEnabled) {
            return
        }

        maintenanceJob = scope.launch {
            while (isActive) {
                val delayMillis = millisUntilNextMaintenance(settings)
                delay(delayMillis)
                if (!isActive) break

                prefs.recordLastLoadStatus("Maintenance refresh")
                showRecoveryOverlay(
                    getString(R.string.recovery_maintenance),
                    getString(R.string.recovery_maintenance_desc)
                )
                clearActiveEngineCache()
                loadDashboard()
            }
        }
    }

    private fun millisUntilNextMaintenance(settings: KioskSettings): Long {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, settings.maintenanceHour)
            set(Calendar.MINUTE, settings.maintenanceMinute)
            if (before(now)) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        return maxOf(60_000L, next.timeInMillis - now.timeInMillis)
    }

    private fun clearActiveEngineCache() {
        when (currentEngine) {
            BrowserEngine.GECKO -> createOrRecreateGeckoSession()
            BrowserEngine.WEBVIEW,
            BrowserEngine.CHROMIUM_CUSTOM_TAB -> binding.webView.clearCache(true)
            null -> Unit
        }
    }

    private fun scheduleWatchdog() {
        watchdogJob?.cancel()
        watchdogFailureCount = 0
        val settings = prefs.load()
        if (!settings.watchdogEnabled) {
            return
        }

        watchdogJob = scope.launch {
            while (isActive) {
                delay(settings.watchdogPingIntervalSeconds * 1_000L)
                val currentSettings = prefs.load()
                if (!currentSettings.watchdogEnabled) break

                val pingUrl = buildWatchdogUrl(currentSettings)
                val healthy = withContext(Dispatchers.IO) { pingUrl(pingUrl) }
                if (healthy) {
                    watchdogFailureCount = 0
                    prefs.recordWatchdogState("ok")
                    if (binding.recoveryOverlay.visibility == View.VISIBLE) {
                        showRecoveryOverlay(getString(R.string.recovery_back_online), pingUrl)
                        mainHandler.postDelayed({ binding.recoveryOverlay.visibility = View.GONE }, 1_800L)
                    }
                } else {
                    watchdogFailureCount += 1
                    prefs.recordWatchdogState("fail($watchdogFailureCount)")
                    showRecoveryOverlay(getString(R.string.recovery_service_unreachable), pingUrl)
                    if (currentSettings.autoReloadOnFailure && watchdogFailureCount >= 2) {
                        scheduleRetry("Watchdog ping failure")
                    }
                    if (watchdogFailureCount >= 4) {
                        recreateCurrentEngine()
                    }
                }
            }
        }
    }

    private fun buildWatchdogUrl(settings: KioskSettings): String {
        val base = KioskPreferences.normalizeBaseUrl(settings.homeAssistantUrl).trimEnd('/')
        val path = KioskPreferences.normalizeDashboardPath(settings.watchdogPingPath)
        return if (path.isBlank()) base else "$base/$path"
    }

    private fun pingUrl(url: String): Boolean {
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 6_000
                instanceFollowRedirects = true
            }
            connection.connect()
            val code = connection.responseCode
            connection.disconnect()
            // Any HTTP response means the server is reachable; treat only transport failures as unreachable.
            code in 100..499
        }.getOrDefault(false)
    }

    private fun recreateCurrentEngine() {
        when (currentEngine) {
            BrowserEngine.GECKO -> createOrRecreateGeckoSession()
            BrowserEngine.WEBVIEW,
            BrowserEngine.CHROMIUM_CUSTOM_TAB -> {
                destroyWebView()
                configureWebViewIfNeeded()
            }
            null -> Unit
        }
        loadDashboard()
    }

    private fun resetAmbientTimer() {
        ambientDimRunnable?.let(mainHandler::removeCallbacks)
        val settings = prefs.load()
        if (!settings.ambientModeEnabled) {
            setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            isAmbientDimmed = false
            return
        }

        if (isAmbientDimmed) {
            setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            isAmbientDimmed = false
        }

        ambientDimRunnable = Runnable {
            val dimLevel = settings.ambientBrightnessPercent / 100f
            setWindowBrightness(dimLevel)
            isAmbientDimmed = true
        }
        mainHandler.postDelayed(ambientDimRunnable!!, settings.ambientDimAfterSeconds * 1_000L)
    }

    private fun applyPresenceWakePolicy() {
        val settings = prefs.load()
        val shouldRunPresenceWake = settings.ambientModeEnabled && settings.presenceWakeEnabled
        if (!shouldRunPresenceWake) {
            presenceWakeRequested = false
            stopPresenceWakePipeline()
            return
        }
        presenceWakeRequested = true

        if (!hasAllPermissions(arrayOf(Manifest.permission.CAMERA))) {
            stopPresenceWakePipeline()
            if (!pendingPresenceWakePermissionRequest) {
                pendingPresenceWakePermissionRequest = true
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            }
            return
        }

        startPresenceWakePipeline()
    }

    private fun ensurePresenceWakeExecutor(): ExecutorService {
        val existing = presenceWakeExecutor
        if (existing != null && !existing.isShutdown) {
            return existing
        }
        return Executors.newSingleThreadExecutor().also { presenceWakeExecutor = it }
    }

    private fun startPresenceWakePipeline() {
        if (presenceWakeAnalysis != null) {
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (!presenceWakeRequested) {
                return@addListener
            }
            val provider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener
            if (!prefs.load().presenceWakeEnabled || !prefs.load().ambientModeEnabled) {
                return@addListener
            }

            val selector = selectPresenceCamera(provider) ?: run {
                prefs.recordLastLoadStatus("Presence wake unavailable: no camera found")
                return@addListener
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(ensurePresenceWakeExecutor()) { imageProxy ->
                analyzePresenceWakeFrame(imageProxy)
            }

            runCatching {
                provider.bindToLifecycle(this, selector, analysis)
            }.onSuccess {
                presenceWakeCameraProvider = provider
                presenceWakeAnalysis = analysis
            }.onFailure { error ->
                analysis.clearAnalyzer()
                prefs.recordLastLoadStatus("Presence wake start failed: ${error.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopPresenceWakePipeline() {
        presenceWakeAnalysis?.clearAnalyzer()
        presenceWakeAnalysis?.let { analysis ->
            runCatching { presenceWakeCameraProvider?.unbind(analysis) }
        }
        presenceWakeAnalysis = null
        isPresenceWakeFrameInFlight = false
    }

    private fun selectPresenceCamera(provider: ProcessCameraProvider): CameraSelector? {
        return when {
            runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false) ->
                CameraSelector.DEFAULT_FRONT_CAMERA
            runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false) ->
                CameraSelector.DEFAULT_BACK_CAMERA
            else -> null
        }
    }

    private fun maybePromptStartupCameraPermission() {
        val hasCameraPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        if (hasCameraPermission) {
            prefs.markStartupCameraPromptShown()
            return
        }
        if (!prefs.shouldShowStartupCameraPrompt()) {
            return
        }

        prefs.markStartupCameraPromptShown()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.startup_camera_prompt_title)
            .setMessage(R.string.startup_camera_prompt_message)
            .setNegativeButton(R.string.not_now, null)
            .setPositiveButton(R.string.allow_camera) { _, _ ->
                pendingStartupCameraPermissionRequest = true
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            }
            .show()
    }

    private fun analyzePresenceWakeFrame(imageProxy: ImageProxy) {
        val settings = prefs.load()
        if (!settings.presenceWakeEnabled || !settings.ambientModeEnabled || !isAmbientDimmed) {
            imageProxy.close()
            return
        }

        if (isPresenceWakeFrameInFlight) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        isPresenceWakeFrameInFlight = true
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        presenceFaceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    maybeWakeFromPresence(settings)
                }
            }
            .addOnCompleteListener {
                isPresenceWakeFrameInFlight = false
                imageProxy.close()
            }
    }

    private fun maybeWakeFromPresence(settings: KioskSettings) {
        if (!isAmbientDimmed) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        val cooldownMs = settings.presenceWakeCooldownSeconds * 1_000L
        if (now - lastPresenceWakeAtMillis < cooldownMs) {
            return
        }

        lastPresenceWakeAtMillis = now
        mainHandler.post {
            if (!isAmbientDimmed) return@post
            setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            isAmbientDimmed = false
            resetAmbientTimer()
        }
    }

    private fun setWindowBrightness(value: Float) {
        val params = window.attributes
        params.screenBrightness = value
        window.attributes = params
    }

    private fun registerNetworkMonitoring() {
        if (networkCallbackRegistered) {
            return
        }
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            networkCallbackRegistered = true
            prefs.recordNetworkState("registered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback", e)
            prefs.recordNetworkState("register_failed")
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
        if (!settings.autoReloadOnFailure) {
            prefs.recordLastLoadStatus("Auto-reload disabled ($reason)")
            return
        }

        val delayMs = settings.reloadIntervalSeconds * 1_000L
        pendingRetry?.let(mainHandler::removeCallbacks)
        pendingRetry = Runnable { loadDashboard() }
        mainHandler.postDelayed(pendingRetry!!, delayMs)
        prefs.recordLastLoadStatus("Reload in ${settings.reloadIntervalSeconds}s ($reason)")

        showRecoveryOverlay(
            getString(R.string.recovery_retry_scheduled),
            getString(R.string.recovery_retry_details, settings.reloadIntervalSeconds)
        )

        Log.w(TAG, "Scheduled reload in ${settings.reloadIntervalSeconds}s. Reason: $reason")
    }

    private fun showRecoveryOverlay(title: String, details: String) {
        binding.recoveryOverlay.visibility = View.VISIBLE
        binding.recoveryMessage.text = title
        binding.recoveryDetails.text = details
    }

    private fun exitKioskNow() {
        attemptStopLockTaskIfRunning()
        finishAndRemoveTask()
    }

    private fun maybeRunAutoUpdateCheck() {
        val settings = prefs.load()
        if (!settings.updatesEnabled || updateCheckInProgress || !KioskPreferences.isValidRepoSlug(settings.updatesRepo)) {
            return
        }

        val now = System.currentTimeMillis()
        val rawPrefs = getSharedPreferences(RAW_PREFS_NAME, MODE_PRIVATE)
        val lastCheck = rawPrefs.getLong(KEY_LAST_UPDATE_CHECK_AT, 0L)
        val cooldown = settings.updateCheckIntervalHours * 3_600_000L
        if (now - lastCheck < cooldown) {
            return
        }

        updateCheckInProgress = true
        rawPrefs.edit().putLong(KEY_LAST_UPDATE_CHECK_AT, now).apply()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                UpdateManager.fetchLatestRelease(settings.updatesRepo)
            }
            updateCheckInProgress = false

            result.onSuccess { release ->
                val currentLabel = UpdateManager.normalizedVersionLabel(currentVersionName())
                val latestLabel = UpdateManager.normalizedVersionLabel(release.tagName)
                val hasUpdate = UpdateManager.isNewerRelease(currentVersionName(), release.tagName)
                prefs.recordUpdateState(
                    if (hasUpdate) {
                        "update $latestLabel available (installed $currentLabel)"
                    } else {
                        "installed $currentLabel is up-to-date (latest $latestLabel)"
                    }
                )
                if (hasUpdate) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_available_toast, latestLabel),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }.onFailure { error ->
                prefs.recordUpdateState("update check failed: ${error.message}")
            }
        }
    }

    companion object {
        private const val TAG = "KioskZenMain"
        private const val ADMIN_GESTURE_TAP_COUNT = 5
        private const val ADMIN_GESTURE_WINDOW_MS = 2_500L
        private const val QUICK_RELOAD_DELAY_MS = 1_200L
        private const val CHROMIUM_FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 15; Tablet) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        private const val RAW_PREFS_NAME = "ha_kiosk_prefs"
        private const val KEY_LAST_UPDATE_CHECK_AT = "last_update_check_at"
        @Volatile
        private var sharedRuntime: GeckoRuntime? = null
        private val geckoRuntimeLock = Any()
    }

    private fun currentVersionName(): String {
        return runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
        }.getOrDefault("0.0.0")
    }
}

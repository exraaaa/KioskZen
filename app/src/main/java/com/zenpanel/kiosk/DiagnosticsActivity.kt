package com.zenpanel.kiosk

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.zenpanel.kiosk.databinding.ActivityDiagnosticsBinding
import java.text.DateFormat
import java.util.Date

class DiagnosticsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDiagnosticsBinding
    private lateinit var prefs: KioskPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = KioskPreferences(this)

        binding.btnRefreshDiagnostics.setOnClickListener { refreshUi() }
        binding.btnExportDiagnostics.setOnClickListener { exportDiagnostics() }
        refreshUi()
    }

    private fun refreshUi() {
        val snapshot = prefs.diagnosticsSnapshot()
        val hasCameraPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        val hasMicPermission =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val online = connectivityManager.activeNetwork != null

        binding.textEngine.text = getString(R.string.diag_engine, snapshot.lastEngine)
        binding.textWebrtc.text = getString(
            R.string.diag_webrtc,
            if (hasCameraPermission) "camera:granted" else "camera:missing",
            if (hasMicPermission) "mic:granted" else "mic:missing"
        )
        binding.textNetwork.text = getString(
            R.string.diag_network,
            if (online) "online" else "offline",
            snapshot.lastNetworkState
        )
        binding.textUrl.text = getString(R.string.diag_url, snapshot.lastUrl)
        binding.textLoad.text = getString(R.string.diag_load, snapshot.lastLoadStatus)
        binding.textWatchdog.text = getString(R.string.diag_watchdog, snapshot.lastWatchdogState)
        binding.textUpdate.text = getString(R.string.diag_update, snapshot.lastUpdateState)
        binding.textCrash.text = getString(
            R.string.diag_crash,
            snapshot.lastCrashReason,
            formatTime(snapshot.lastCrashAtMillis)
        )
    }

    private fun exportDiagnostics() {
        val snapshot = prefs.diagnosticsSnapshot()
        val content = buildString {
            appendLine("KioskZen diagnostics")
            appendLine("engine=${snapshot.lastEngine}")
            appendLine("url=${snapshot.lastUrl}")
            appendLine("load=${snapshot.lastLoadStatus}")
            appendLine("network=${snapshot.lastNetworkState}")
            appendLine("watchdog=${snapshot.lastWatchdogState}")
            appendLine("update=${snapshot.lastUpdateState}")
            appendLine("crash=${snapshot.lastCrashReason}")
            appendLine("crashAt=${formatTime(snapshot.lastCrashAtMillis)}")
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "KioskZen diagnostics")
            putExtra(Intent.EXTRA_TEXT, content)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.export_diagnostics)))
    }

    private fun formatTime(timestamp: Long): String {
        if (timestamp <= 0L) return "-"
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
            .format(Date(timestamp))
    }
}

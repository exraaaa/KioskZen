package com.zenpanel.kiosk

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
    val publishedAt: String,
    val apkAsset: ReleaseAsset?
)

object UpdateManager {
    private const val GITHUB_API_BASE = "https://api.github.com/repos/"
    private val SEMVER_FINDER = Regex("""(?i)\b[vV]?(\d+(?:\.\d+){0,3})(?:-([0-9A-Za-z.-]+))?""")

    fun fetchLatestRelease(repoSlug: String): Result<ReleaseInfo> {
        return runCatching {
            require(KioskPreferences.isValidRepoSlug(repoSlug)) { "Invalid repo. Use owner/repo." }
            val url = URL("${GITHUB_API_BASE}${repoSlug.trim()}/releases/latest")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 12_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "KioskZen-UpdateChecker")
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
            val response = stream.use { input ->
                BufferedReader(InputStreamReader(input)).use { reader ->
                    buildString {
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            append(line)
                        }
                    }
                }
            }
            connection.disconnect()

            require(code in 200..299) { "GitHub API returned HTTP $code" }
            parseRelease(JSONObject(response))
        }
    }

    fun isNewerRelease(currentVersion: String, releaseTag: String): Boolean {
        val current = parseVersion(currentVersion)
        val latest = parseVersion(releaseTag)

        if (current == null || latest == null) {
            return false
        }
        return compareVersions(current, latest) < 0
    }

    fun normalizedVersionLabel(raw: String): String {
        val parsed = parseVersion(raw) ?: return raw.trim()
        val base = parsed.core.joinToString(".")
        return if (parsed.preRelease.isEmpty()) base else "$base-${parsed.preRelease.joinToString(".")}"
    }

    fun enqueueDownload(
        context: Context,
        releaseInfo: ReleaseInfo,
        appLabel: String
    ): Long {
        val asset = releaseInfo.apkAsset
            ?: throw IllegalArgumentException("No APK asset in latest release.")
        val request = DownloadManager.Request(Uri.parse(asset.downloadUrl)).apply {
            setTitle("$appLabel ${releaseInfo.tagName}")
            setDescription("Downloading ${asset.name}")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, asset.name)
        }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return manager.enqueue(request)
    }

    fun queryDownloadedUri(context: Context, downloadId: Long): Uri? {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        manager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) return null
            return manager.getUriForDownloadedFile(downloadId)
        }
    }

    private fun parseRelease(json: JSONObject): ReleaseInfo {
        val assets = json.optJSONArray("assets") ?: JSONArray()
        val apk = findApkAsset(assets)
        return ReleaseInfo(
            tagName = json.optString("tag_name"),
            name = json.optString("name"),
            body = json.optString("body"),
            htmlUrl = json.optString("html_url"),
            publishedAt = json.optString("published_at"),
            apkAsset = apk
        )
    }

    private fun findApkAsset(assets: JSONArray): ReleaseAsset? {
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val url = asset.optString("browser_download_url")
            if (url.isBlank()) continue
            return ReleaseAsset(
                name = name,
                downloadUrl = url,
                sizeBytes = asset.optLong("size")
            )
        }
        return null
    }

    private data class ParsedVersion(
        val core: List<Int>,
        val preRelease: List<String>
    )

    private fun parseVersion(raw: String): ParsedVersion? {
        val match = SEMVER_FINDER.find(raw.trim()) ?: return null
        val core = match.groupValues[1]
            .split('.')
            .mapNotNull { it.toIntOrNull() }
        if (core.isEmpty()) return null

        val preRelease = match.groupValues.getOrNull(2)
            .orEmpty()
            .split('.')
            .filter { it.isNotBlank() }

        return ParsedVersion(core = core, preRelease = preRelease)
    }

    private fun compareVersions(a: ParsedVersion, b: ParsedVersion): Int {
        val max = maxOf(a.core.size, b.core.size)
        for (index in 0 until max) {
            val left = a.core.getOrElse(index) { 0 }
            val right = b.core.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }

        val aPre = a.preRelease
        val bPre = b.preRelease
        if (aPre.isEmpty() && bPre.isEmpty()) return 0
        if (aPre.isEmpty()) return 1
        if (bPre.isEmpty()) return -1

        val preMax = maxOf(aPre.size, bPre.size)
        for (index in 0 until preMax) {
            val left = aPre.getOrNull(index) ?: return -1
            val right = bPre.getOrNull(index) ?: return 1
            val leftNum = left.toIntOrNull()
            val rightNum = right.toIntOrNull()

            val result = when {
                leftNum != null && rightNum != null -> leftNum.compareTo(rightNum)
                leftNum != null -> -1
                rightNum != null -> 1
                else -> left.compareTo(right)
            }

            if (result != 0) return result
        }
        return 0
    }
}

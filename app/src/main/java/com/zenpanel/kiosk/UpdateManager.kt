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
        val currentParts = versionParts(currentVersion)
        val latestParts = versionParts(releaseTag)
        val max = maxOf(currentParts.size, latestParts.size)
        for (index in 0 until max) {
            val current = currentParts.getOrElse(index) { 0 }
            val latest = latestParts.getOrElse(index) { 0 }
            if (latest > current) return true
            if (latest < current) return false
        }
        return false
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

    private fun versionParts(raw: String): List<Int> {
        val normalized = raw.trim().removePrefix("v").substringBefore('-')
        return normalized.split('.').mapNotNull { it.toIntOrNull() }
    }
}

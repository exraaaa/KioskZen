package com.zenpanel.kiosk

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.Collections
import java.util.Enumeration
import java.util.LinkedHashSet
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class HomeAssistantDiscoveryResult(
    val baseUrl: String,
    val source: String
)

object HomeAssistantDiscovery {
    private const val DEFAULT_HA_PORT = 8123
    private const val QUICK_PROBE_TIMEOUT_MS = 900
    private const val SCAN_PROBE_TIMEOUT_MS = 650
    private const val MAX_SCAN_DURATION_MS = 12_000L
    private const val SCAN_THREADS = 40

    fun discover(context: Context): HomeAssistantDiscoveryResult? {
        return discoverCandidates(context, maxResults = 1).firstOrNull()
    }

    fun discoverCandidates(
        context: Context,
        maxResults: Int = 12
    ): List<HomeAssistantDiscoveryResult> {
        if (!isNetworkAvailable(context)) {
            return emptyList()
        }

        val results = LinkedHashSet<String>()
        val labeledResults = mutableListOf<HomeAssistantDiscoveryResult>()

        fun addResult(url: String, source: String) {
            if (results.add(url)) {
                labeledResults += HomeAssistantDiscoveryResult(url, source)
            }
        }

        val quickCandidates = buildQuickCandidates()
        quickCandidates.forEach { candidate ->
            if (probeHomeAssistant(candidate, QUICK_PROBE_TIMEOUT_MS)) {
                addResult(candidate, "quick")
            }
            if (labeledResults.size >= maxResults) {
                return labeledResults.take(maxResults)
            }
        }

        val localIp = findPrimaryPrivateIpv4() ?: return labeledResults.take(maxResults)
        val prefix = localIp.substringBeforeLast('.', missingDelimiterValue = "")
        if (prefix.isBlank()) {
            return labeledResults.take(maxResults)
        }

        val selfLastOctet = localIp.substringAfterLast('.', missingDelimiterValue = "")
            .toIntOrNull()

        val hostOrder = buildHostOrder(selfLastOctet)
        val candidates = hostOrder.map { host ->
            "http://$prefix.$host:$DEFAULT_HA_PORT"
        }

        val executor = Executors.newFixedThreadPool(SCAN_THREADS)
        val completion = ExecutorCompletionService<String?>(executor)
        val futures = mutableListOf<java.util.concurrent.Future<String?>>()
        val deadline = System.currentTimeMillis() + MAX_SCAN_DURATION_MS

        return try {
            candidates.forEach { candidate ->
                futures += completion.submit {
                    if (Thread.currentThread().isInterrupted) {
                        return@submit null
                    }
                    if (probeHomeAssistant(candidate, SCAN_PROBE_TIMEOUT_MS)) candidate else null
                }
            }

            repeat(candidates.size) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) {
                    return labeledResults.take(maxResults)
                }
                val finished = completion.poll(remaining, TimeUnit.MILLISECONDS)
                    ?: return labeledResults.take(maxResults)
                val found = runCatching { finished.get() }.getOrNull()
                if (!found.isNullOrBlank()) {
                    addResult(found, "scan")
                    if (labeledResults.size >= maxResults) {
                        return labeledResults.take(maxResults)
                    }
                }
            }
            labeledResults.take(maxResults)
        } finally {
            futures.forEach { it.cancel(true) }
            executor.shutdownNow()
        }
    }

    private fun buildQuickCandidates(): List<String> {
        val set = LinkedHashSet<String>()
        set += "http://homeassistant.local:$DEFAULT_HA_PORT"
        set += "https://homeassistant.local:$DEFAULT_HA_PORT"
        set += "http://hass.local:$DEFAULT_HA_PORT"
        set += "https://hass.local:$DEFAULT_HA_PORT"
        return set.toList()
    }

    private fun buildHostOrder(selfLastOctet: Int?): List<Int> {
        val ordered = linkedSetOf<Int>()
        ordered += 1
        if (selfLastOctet != null) {
            for (offset in 0..32) {
                val lower = selfLastOctet - offset
                val upper = selfLastOctet + offset
                if (lower in 2..254) ordered += lower
                if (upper in 2..254) ordered += upper
            }
        }
        for (host in 2..254) {
            ordered += host
        }
        return ordered.toList()
    }

    private fun probeHomeAssistant(baseUrl: String, timeoutMs: Int): Boolean {
        if (looksLikeHomeAssistantApi(baseUrl, timeoutMs)) {
            return true
        }
        return looksLikeHomeAssistantRoot(baseUrl, timeoutMs)
    }

    private fun looksLikeHomeAssistantApi(baseUrl: String, timeoutMs: Int): Boolean {
        val connection = openConnection("$baseUrl/api/", timeoutMs) ?: return false
        return try {
            connection.requestMethod = "GET"
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
                return true
            }
            val serverHeader = connection.getHeaderField("Server").orEmpty().lowercase()
            code in 200..399 && serverHeader.contains("home assistant")
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun looksLikeHomeAssistantRoot(baseUrl: String, timeoutMs: Int): Boolean {
        val connection = openConnection("$baseUrl/", timeoutMs) ?: return false
        return try {
            connection.requestMethod = "GET"
            val code = connection.responseCode
            if (code !in 200..499) {
                return false
            }

            val serverHeader = connection.getHeaderField("Server").orEmpty().lowercase()
            if (serverHeader.contains("home assistant")) {
                return true
            }

            val body = runCatching {
                connection.inputStream.bufferedReader().use { reader ->
                    val builder = StringBuilder()
                    val chunk = CharArray(1024)
                    var total = 0
                    while (total < 4096) {
                        val read = reader.read(chunk, 0, minOf(chunk.size, 4096 - total))
                        if (read <= 0) break
                        builder.append(chunk, 0, read)
                        total += read
                    }
                    builder.toString()
                }
            }.getOrDefault("")

            val normalized = body.lowercase()
            normalized.contains("home assistant") || normalized.contains("ha-auth-flow")
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, timeoutMs: Int): HttpURLConnection? {
        return runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", "KioskZen-Discovery")
                setRequestProperty("Accept", "text/html,application/json,*/*")
            }
        }.getOrNull()
    }

    private fun findPrimaryPrivateIpv4(): String? {
        val preferred = mutableListOf<String>()
        val fallback = mutableListOf<String>()
        asList(NetworkInterface.getNetworkInterfaces()).forEach { iface ->
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) {
                return@forEach
            }
            asList(iface.inetAddresses).forEach { address ->
                val host = (address as? Inet4Address)?.hostAddress.orEmpty()
                if (host.isBlank() || !isPrivateIpv4(host)) {
                    return@forEach
                }
                if (iface.name.contains("wlan", ignoreCase = true) ||
                    iface.name.contains("wifi", ignoreCase = true)
                ) {
                    preferred += host
                } else {
                    fallback += host
                }
            }
        }
        return preferred.firstOrNull() ?: fallback.firstOrNull()
    }

    private fun isPrivateIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val first = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val second = parts.getOrNull(1)?.toIntOrNull() ?: return false
        return (first == 10) ||
            (first == 192 && second == 168) ||
            (first == 172 && second in 16..31)
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun <T> asList(enumeration: Enumeration<T>?): List<T> {
        if (enumeration == null) return emptyList()
        return Collections.list(enumeration)
    }
}

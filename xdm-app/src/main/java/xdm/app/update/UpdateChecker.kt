package xdm.app.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xdm.core.util.Logger
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bundled update configuration, read from `/app-version.json` on the classpath.
 *
 * - [currentVersion]  the version this build identifies itself as.
 * - [updateCheckUrl]  endpoint that returns the latest release info (GitHub
 *                     releases API by default).
 * - [downloadUrl]     page opened when the user clicks the update button.
 */
@Serializable
data class AppVersionConfig(
    val currentVersion: String,
    val updateCheckUrl: String,
    val downloadUrl: String
)

/** Subset of the GitHub "latest release" response we care about. */
@Serializable
private data class GithubRelease(
    val tag_name: String = "",
    val name: String = "",
    val html_url: String = ""
)

/** Result handed to the UI when a newer release is found. */
data class UpdateInfo(val latestVersion: String, val downloadUrl: String)

object UpdateChecker {

    private val json = Json { ignoreUnknownKeys = true }

    /** Loads the bundled [AppVersionConfig], or null if it is missing/invalid. */
    fun loadConfig(): AppVersionConfig? {
        return try {
            val stream: InputStream? = UpdateChecker::class.java.getResourceAsStream("/app-version.json")
            if (stream == null) {
                Logger.info("UPDATE", "app-version.json not found on classpath")
                return null
            }
            stream.use { json.decodeFromString<AppVersionConfig>(it.readBytes().decodeToString()) }
        } catch (e: Exception) {
            Logger.error("UPDATE", "Failed to read app-version.json", e)
            null
        }
    }

    /**
     * Checks for a newer release on a background thread. [callback] is invoked
     * with an [UpdateInfo] when a newer version exists, or with null otherwise
     * (no update / network failure / config missing). The callback runs on the
     * background thread; marshal to the EDT in the UI layer.
     */
    fun checkForUpdate(callback: (UpdateInfo?) -> Unit) {
        Thread {
            callback(runCatching { check() }.getOrElse {
                Logger.error("UPDATE", "Update check failed", it)
                null
            })
        }.apply {
            isDaemon = true
            name = "xdm-update-checker"
        }.start()
    }

    private fun check(): UpdateInfo? {
        val config = loadConfig() ?: return null
        val latest = fetchLatestVersion(config.updateCheckUrl) ?: return null
        return if (compareVersions(latest, config.currentVersion) > 0) {
            Logger.info("UPDATE", "Newer version available: $latest (current ${config.currentVersion})")
            UpdateInfo(latestVersion = latest.removePrefix("v").removePrefix("V"), downloadUrl = config.downloadUrl)
        } else {
            Logger.info("UPDATE", "Already up to date (current ${config.currentVersion}, latest $latest)")
            null
        }
    }

    private fun fetchLatestVersion(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            // GitHub's API rejects requests without a User-Agent.
            setRequestProperty("User-Agent", "XDM-Update-Checker")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode !in 200..299) {
                Logger.info("UPDATE", "Update check HTTP ${conn.responseCode}")
                return null
            }
            val body = conn.inputStream.use { it.readBytes().decodeToString() }
            val release = json.decodeFromString<GithubRelease>(body)
            val tag = release.tag_name.ifBlank { release.name }
            return tag.ifBlank { null }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Compares two dotted version strings (an optional leading `v` is ignored).
     * Returns > 0 if [v1] is newer than [v2], < 0 if older, 0 if equal.
     */
    fun compareVersions(v1: String, v2: String): Int {
        val a = normalize(v1)
        val b = normalize(v2)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun normalize(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '_')
            .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }
}

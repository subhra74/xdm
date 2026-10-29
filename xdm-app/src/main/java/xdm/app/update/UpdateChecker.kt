package xdm.app.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import xdm.app.AppContext
import xdm.core.network.http.impl.HttpClientImpl
import xdm.core.util.Logger
import java.io.InputStream

/**
 * Bundled update configuration, read from `/app-version.json` on the classpath.
 *
 * - [currentVersion]  the version this build identifies itself as.
 * - [updateCheckUrl]  endpoint that returns release info: the GitHub releases list (which, unlike
 *                     `/releases/latest`, includes pre-releases) or a single release object.
 * - [downloadUrl]     page opened when the user clicks the update button.
 */
@Serializable
data class AppVersionConfig(
    val currentVersion: String,
    val updateCheckUrl: String,
    val downloadUrl: String
)

/**
 * Subset of a GitHub release we care about: the version and whether it is a pre-release. The release
 * page itself is never opened - updates go through [AppVersionConfig.downloadUrl], our website.
 */
@Serializable
internal data class GithubRelease(
    val tag_name: String = "",
    val name: String = "",
    val prerelease: Boolean = false,
    val draft: Boolean = false
)

/** A release version found by the check; [isPreRelease] is GitHub's flag or a `-suffix` on the tag. */
internal data class ReleaseVersion(val version: String, val isPreRelease: Boolean)

/**
 * Result handed to the UI when a newer release is found; [downloadUrl] is always our website, also
 * for a pre-release.
 */
data class UpdateInfo(val latestVersion: String, val downloadUrl: String, val isPreRelease: Boolean)

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
        val body = fetchReleases(config.updateCheckUrl) ?: return null
        val latest = newestRelease(body) ?: return null
        return if (compareVersions(latest.version, config.currentVersion) > 0) {
            Logger.info("UPDATE", "Newer version available: ${latest.version}" +
                    "${if (latest.isPreRelease) " (pre-release)" else ""} (current ${config.currentVersion})")
            UpdateInfo(
                latestVersion = latest.version.trim().removePrefix("v").removePrefix("V"),
                downloadUrl = config.downloadUrl,
                isPreRelease = latest.isPreRelease
            )
        } else {
            Logger.info("UPDATE", "Already up to date (current ${config.currentVersion}, latest ${latest.version})")
            null
        }
    }

    /**
     * Picks the highest-versioned release from a GitHub response [body]: either the releases list or a
     * single release object (`/releases/latest`). Drafts are skipped; null if nothing usable is found.
     */
    internal fun newestRelease(body: String): ReleaseVersion? {
        val element = json.parseToJsonElement(body)
        val items: List<JsonElement> = if (element is JsonArray) element else listOf(element)
        return items
            .mapNotNull { runCatching { json.decodeFromJsonElement<GithubRelease>(it) }.getOrNull() }
            .filterNot { it.draft }
            .mapNotNull { release ->
                val tag = release.tag_name.ifBlank { release.name }.trim()
                if (tag.isEmpty()) null
                else ReleaseVersion(tag, release.prerelease || splitVersion(tag).second.isNotEmpty())
            }
            .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
    }

    /**
     * Through OkHttp and the shared TLS context like every download, and through the configured proxy.
     * Never trust-all and never a login prompt: it is a background fetch the user did not start.
     */
    private fun fetchReleases(url: String): String? {
        val config = AppContext.config
        val client = HttpClientImpl(
            1, config.toProxy(), readTimeoutSeconds = 10,
            proxyUser = config.proxyUser, proxyPassword = config.proxyPass,
        )
        try {
            // GitHub's API rejects requests without a User-Agent.
            val headers = mapOf(
                "User-Agent" to listOf("XDM-Update-Checker"),
                "Accept" to listOf("application/vnd.github+json"),
            )
            return client.getResponse(url, headers, null, null).getOrThrow().use { response ->
                if (response.statusCode !in 200..299) {
                    Logger.info("UPDATE", "Update check HTTP ${response.statusCode}")
                    null
                } else {
                    response.inputStream.readBytes().decodeToString()
                }
            }
        } finally {
            client.close()
        }
    }

    /**
     * Compares two versions semver-style (an optional leading `v` is ignored).
     * Returns > 0 if [v1] is newer than [v2], < 0 if older, 0 if equal.
     *
     * The dotted numeric core is compared first. With equal cores a release beats a pre-release
     * (`9.1.0` > `9.1.0-rc.1`); two pre-releases compare their suffix piece by piece, numbers
     * numerically (`beta.2` < `beta.10`) and text alphabetically (`alpha` < `beta` < `rc`).
     */
    fun compareVersions(v1: String, v2: String): Int {
        val (coreA, preA) = splitVersion(v1)
        val (coreB, preB) = splitVersion(v2)
        for (i in 0 until maxOf(coreA.size, coreB.size)) {
            val x = coreA.getOrElse(i) { 0 }
            val y = coreB.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        when {
            preA.isEmpty() && preB.isEmpty() -> return 0
            preA.isEmpty() -> return 1
            preB.isEmpty() -> return -1
        }
        for (i in 0 until minOf(preA.size, preB.size)) {
            val c = comparePreReleasePart(preA[i], preB[i])
            if (c != 0) return c
        }
        return preA.size.compareTo(preB.size)
    }

    /** Numeric parts sort before text parts, as in semver. */
    private fun comparePreReleasePart(a: String, b: String): Int {
        val x = a.toIntOrNull()
        val y = b.toIntOrNull()
        return when {
            x != null && y != null -> x.compareTo(y)
            x != null -> -1
            y != null -> 1
            else -> a.compareTo(b, ignoreCase = true)
        }
    }

    /**
     * Splits `v9.1.0-beta.2` into its numeric core `[9, 1, 0]` and pre-release parts `[beta, 2]`.
     * Build metadata (`+...`) is dropped.
     */
    internal fun splitVersion(v: String): Pair<List<Int>, List<String>> {
        val s = v.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val dash = s.indexOf('-')
        val core = if (dash < 0) s else s.substring(0, dash)
        val pre = if (dash < 0) "" else s.substring(dash + 1)
        return core.split('.').mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() } to
                pre.split('.', '-', '_').filter { it.isNotEmpty() }
    }
}

package xdm.app.ytdlp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import xdm.app.OS
import java.util.Locale

/** The processor family the tool binaries are built for. */
enum class Arch { X64, ARM64 }

fun currentArch(): Arch? = when (System.getProperty("os.arch")?.lowercase(Locale.ROOT)) {
    "amd64", "x86_64", "x64" -> Arch.X64
    "aarch64", "arm64" -> Arch.ARM64
    else -> null
}

/**
 * Where a tool's binary for one OS and processor is published: a GitHub release asset.
 *
 * - [entry]: for a zip, the binary's path inside it, matched at the end of the entry name.
 * - [checksumAsset]: a checksum file in the same release, checked as well as GitHub's own digest.
 *   `{asset}` stands for the asset's name.
 * - [tag]: a fixed release (a moving `latest` build); null for the repository's newest releases.
 */
data class ToolSource(
    val repo: String,
    val asset: String,
    val entry: String? = null,
    val checksumAsset: String? = null,
    val tag: String? = null,
)

/**
 * The official release each tool is downloaded from (docs: `private-docs/docs/ytdlp-download.md` §3.2).
 */
object ToolSources {
    fun of(tool: ExternalTool, os: OS, arch: Arch): ToolSource? = when (tool) {
        ExternalTool.YT_DLP -> ToolSource(
            repo = "yt-dlp/yt-dlp",
            asset = when (os) {
                OS.Windows -> if (arch == Arch.ARM64) "yt-dlp_arm64.exe" else "yt-dlp.exe"
                // Universal: one binary for both processors.
                OS.MacOS -> "yt-dlp_macos"
                OS.Linux -> if (arch == Arch.ARM64) "yt-dlp_linux_aarch64" else "yt-dlp_linux"
            },
            checksumAsset = "SHA2-256SUMS",
        )

        ExternalTool.DENO -> {
            val triple = when (os) {
                OS.Windows -> "pc-windows-msvc"
                OS.MacOS -> "apple-darwin"
                OS.Linux -> "unknown-linux-gnu"
            }
            val cpu = if (arch == Arch.ARM64) "aarch64" else "x86_64"
            ToolSource(
                repo = "denoland/deno",
                asset = "deno-$cpu-$triple.zip",
                entry = ExternalTool.DENO.exeName(os),
                checksumAsset = "{asset}.sha256sum",
            )
        }
    }

    /** The API call that lists the releases to look in. */
    fun releasesUrl(source: ToolSource, apiBase: String = GITHUB_API): String =
        if (source.tag != null) "$apiBase/repos/${source.repo}/releases/tags/${source.tag}"
        // Not /releases/latest: the newest release may lack the asset (it is still being built, or a
        // platform was dropped); the newest few are searched instead.
        else "$apiBase/repos/${source.repo}/releases?per_page=$RELEASES_SCANNED"

    const val GITHUB_API = "https://api.github.com"
    const val RELEASES_SCANNED = 5
}

data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String?)

data class Release(val tag: String, val publishedAt: String, val prerelease: Boolean, val assets: List<ReleaseAsset>)

/** The release and asset a tool would be installed from. */
data class ToolRelease(val source: ToolSource, val release: Release, val asset: ReleaseAsset) {
    /** For the Tools section: the tag, with the build date for a moving `latest` build. */
    val label: String
        get() = if (source.tag != null) "${release.tag} ${release.publishedAt.take(10)}" else release.tag
}

internal object GithubReleases {
    private val json = Json { ignoreUnknownKeys = true }

    /** A releases list or a single release, as the GitHub API returns them. Drafts are left out. */
    fun parse(body: String): List<Release> {
        val root = json.parseToJsonElement(body)
        val items: List<JsonElement> = if (root is JsonArray) root else listOf(root)
        return items.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            if (o.bool("draft")) return@mapNotNull null
            val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { a ->
                val ao = a as? JsonObject ?: return@mapNotNull null
                val name = ao.str("name") ?: return@mapNotNull null
                val url = ao.str("browser_download_url") ?: return@mapNotNull null
                val digest = ao.str("digest")
                ReleaseAsset(
                    name = name, url = url, size = ao["size"]?.jsonPrimitive?.longOrNull ?: -1,
                    sha256 = digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(Locale.ROOT),
                )
            }
            Release(
                tag = o.str("tag_name") ?: "", publishedAt = o.str("published_at") ?: "",
                prerelease = o.bool("prerelease"), assets = assets,
            )
        }
    }

    /** The newest stable release, in the API's order, that has [source]'s asset. */
    fun pick(source: ToolSource, releases: List<Release>): ToolRelease? = releases
        .filter { !it.prerelease || source.tag != null }
        .firstNotNullOfOrNull { r -> r.assets.firstOrNull { it.name == source.asset }?.let { ToolRelease(source, r, it) } }

    private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean = str(key) == "true"
}

/**
 * The SHA-256 of [assetName] in a checksum file: `<hex>  <name>` lines (`*name` for binary mode), or
 * a file that holds only the hash. Null when the file does not list it.
 */
internal fun checksumFor(text: String, assetName: String): String? {
    val hex = Regex("^[0-9a-fA-F]{64}$")
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    for (line in lines) {
        val parts = line.split(Regex("\\s+"), limit = 2)
        if (parts.size == 2 && hex.matches(parts[0]) && parts[1].removePrefix("*").substringAfterLast('/') == assetName) {
            return parts[0].lowercase(Locale.ROOT)
        }
    }
    // A single-hash file (or PowerShell's "SHA256  <hex>  <path>" table).
    val all = lines.flatMap { it.split(Regex("\\s+")) }.filter { hex.matches(it) }
    return all.singleOrNull()?.lowercase(Locale.ROOT)
}

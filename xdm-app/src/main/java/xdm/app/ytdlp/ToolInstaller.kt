package xdm.app.ytdlp

import xdm.app.AppContext
import xdm.app.OS
import xdm.app.utils.detectOS
import xdm.core.network.http.impl.HttpClientImpl
import xdm.core.util.AtomicIO
import xdm.core.util.Logger
import xdm.core.util.TaggedFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.ZipInputStream

/** A GET for the installer; an interface so tests can serve fake releases. */
interface ToolHttp {
    class Response(val status: Int, val length: Long?, val body: InputStream) : AutoCloseable {
        override fun close() = body.close()
    }

    fun get(url: String, accept: String): Response
}

/** Through OkHttp and the configured proxy, like the app's update check. One client per install. */
class OkToolHttp : ToolHttp, AutoCloseable {
    private val client = AppContext.config.let { c ->
        HttpClientImpl(1, c.toProxy(), readTimeoutSeconds = 30, proxyUser = c.proxyUser, proxyPassword = c.proxyPass)
    }

    override fun get(url: String, accept: String): ToolHttp.Response {
        // GitHub's API rejects requests without a User-Agent.
        val headers = mapOf("User-Agent" to listOf("XDM-Video-Download"), "Accept" to listOf(accept))
        val r = client.getResponse(url, headers, null, null).getOrThrow()
        return ToolHttp.Response(r.statusCode, r.contentLength, object : java.io.FilterInputStream(r.inputStream) {
            override fun close() = r.close()
        })
    }

    override fun close() = client.close()
}

/** What `tools/versions` records about an installed tool. */
data class InstalledTool(val tool: ExternalTool, val repo: String, val tag: String, val asset: String, val sha256: String)

class ToolInstallException(message: String) : IOException(message)

/**
 * Downloads yt-dlp and deno into XDM's `tools` folder from their GitHub releases, and updates
 * them there. Only that folder is touched: a custom or system tool is never replaced.
 *
 * Every file is checked against the SHA-256 GitHub publishes for the asset and, where the release has
 * one, its checksum file. The new binary is started once (`--version`) before it replaces the old one.
 *
 * No origin mark is added: the fetch does not go through the download manager, so
 * `markDownloadedFile` never runs. On macOS a quarantine attribute that got there some other way is
 * removed, or Gatekeeper would block the unsigned binaries. On Windows a `Zone.Identifier` stream is
 * left alone: removing it from fresh executables is what malware does, and antivirus flags it. It does
 * not matter there, as the tools are started with CreateProcess, which does no zone checks.
 */
object ToolInstaller {
    /**
     * Held for reading while a tool runs (a yt-dlp lookup), for writing while one is
     * replaced: an exe that is running cannot be replaced on Windows, and swapping it mid-run is
     * unsafe elsewhere too.
     */
    val inUse = ReentrantReadWriteLock()

    private const val VERSIONS_FILE = "versions"
    private const val F_TOOL = 1
    private const val T_ID = 1
    private const val T_REPO = 2
    private const val T_TAG = 3
    private const val T_ASSET = 4
    private const val T_SHA256 = 5

    fun installed(dir: File = ExternalTools.toolsDir): Map<ExternalTool, InstalledTool> {
        if (!AtomicIO.exists(VERSIONS_FILE, dir.path)) return emptyMap()
        return AtomicIO.readTransacted(VERSIONS_FILE, dir.path) { input ->
            val r = TaggedFile.read(input)
            val result = LinkedHashMap<ExternalTool, InstalledTool>()
            while (true) {
                when (r.next()) {
                    -1 -> break
                    F_TOOL -> {
                        val t = r.message()
                        var id = ""; var repo = ""; var tag = ""; var asset = ""; var sha = ""
                        while (true) {
                            when (t.next()) {
                                -1 -> break
                                T_ID -> id = t.string()
                                T_REPO -> repo = t.string()
                                T_TAG -> tag = t.string()
                                T_ASSET -> asset = t.string()
                                T_SHA256 -> sha = t.string()
                                else -> t.skip()
                            }
                        }
                        ExternalTool.entries.firstOrNull { it.id == id }?.let { result[it] = InstalledTool(it, repo, tag, asset, sha) }
                    }

                    else -> r.skip()
                }
            }
            result as Map<ExternalTool, InstalledTool>
        }.onFailure { Logger.error("ytdlp", "Could not read $VERSIONS_FILE", it) }.getOrDefault(emptyMap())
    }

    private fun saveInstalled(dir: File, tools: Map<ExternalTool, InstalledTool>) {
        AtomicIO.writeTransacted(VERSIONS_FILE, dir.path) { out ->
            TaggedFile.write(out) {
                tools.values.forEach { t ->
                    message(F_TOOL) {
                        string(T_ID, t.tool.id)
                        string(T_REPO, t.repo)
                        string(T_TAG, t.tag)
                        string(T_ASSET, t.asset)
                        string(T_SHA256, t.sha256)
                    }
                }
            }
        }.getOrThrow()
    }

    /** The release the tool would be installed or updated from now. */
    fun latest(tool: ExternalTool, http: ToolHttp, os: OS = detectOS(), arch: Arch? = currentArch(),
               apiBase: String = ToolSources.GITHUB_API): ToolRelease {
        val source = arch?.let { ToolSources.of(tool, os, it) }
            ?: throw ToolInstallException("No ${tool.id} build for this computer")
        val body = http.get(ToolSources.releasesUrl(source, apiBase), "application/vnd.github+json").use { r ->
            if (r.status !in 200..299) throw ToolInstallException("GitHub answered ${r.status} for ${source.repo}")
            r.body.readBytes().decodeToString()
        }
        return GithubReleases.pick(source, GithubReleases.parse(body))
            ?: throw ToolInstallException("No release of ${source.repo} has ${source.asset}")
    }

    /** Whether [latest] differs from what is installed in the `tools` folder. True when nothing is. */
    fun isNewer(installed: InstalledTool?, latest: ToolRelease): Boolean =
        installed == null || installed.asset != latest.asset.name ||
            (latest.asset.sha256 != null && installed.sha256 != latest.asset.sha256) ||
            (latest.asset.sha256 == null && installed.tag != latest.release.tag)

    /**
     * Downloads [release] into [dir] and puts it in place of the tool's binary there. [progress] gets
     * bytes so far and the total (when known). Throws on any failure, the old binary kept.
     */
    fun install(
        release: ToolRelease, tool: ExternalTool, http: ToolHttp, cancelled: AtomicBoolean,
        progress: (Long, Long?) -> Unit, dir: File = ExternalTools.toolsDir, os: OS = detectOS(),
        runCheck: (File) -> Boolean = { checkStarts(tool, it) },
    ) {
        val lock = inUse.writeLock()
        if (!lock.tryLock(0, TimeUnit.SECONDS)) throw ToolInstallException("busy")
        try {
            installLocked(release, tool, http, cancelled, progress, dir, os, runCheck)
        } finally {
            lock.unlock()
        }
    }

    private fun installLocked(
        release: ToolRelease, tool: ExternalTool, http: ToolHttp, cancelled: AtomicBoolean,
        progress: (Long, Long?) -> Unit, dir: File, os: OS, runCheck: (File) -> Boolean,
    ) {
        val tmpDir = File(dir, ".tmp")
        if (!tmpDir.isDirectory && !tmpDir.mkdirs()) throw ToolInstallException("Cannot create $tmpDir")
        val download = File(tmpDir, release.asset.name)
        val binary = File(tmpDir, tool.exeName(os))
        try {
            val expected = expectedHashes(release, http)
            if (expected.isEmpty()) throw ToolInstallException("${release.asset.name} has no published checksum")
            val actual = fetch(release.asset, http, download, cancelled, progress)
            if (expected.any { it != actual }) {
                throw ToolInstallException("Checksum mismatch for ${release.asset.name}")
            }
            if (release.source.entry != null) {
                unzipEntry(download, release.source.entry, binary)
                download.delete()
            } else if (download != binary) {
                Files.move(download.toPath(), binary.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            if (os != OS.Windows && !binary.setExecutable(true)) throw ToolInstallException("Cannot make $binary executable")
            if (os == OS.MacOS) removeQuarantine(binary)
            if (cancelled.get()) throw ToolInstallException("cancelled")
            if (!runCheck(binary)) throw ToolInstallException("${tool.id} does not start on this computer")
            val target = File(dir, tool.exeName(os))
            try {
                Files.move(binary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                Files.move(binary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            val all = LinkedHashMap(installed(dir))
            all[tool] = InstalledTool(tool, release.source.repo, release.release.tag, release.asset.name, actual)
            saveInstalled(dir, all)
            Logger.info("ytdlp", "Installed ${tool.id} ${release.label} from ${release.source.repo}")
        } finally {
            download.delete()
            binary.delete()
        }
    }

    /** GitHub's digest and the release's checksum file, whichever exist. */
    private fun expectedHashes(release: ToolRelease, http: ToolHttp): List<String> {
        val hashes = ArrayList<String>()
        release.asset.sha256?.let { hashes.add(it) }
        val sumsName = release.source.checksumAsset?.replace("{asset}", release.asset.name)
        val sums = sumsName?.let { n -> release.release.assets.firstOrNull { it.name == n } }
        if (sums != null) {
            val text = http.get(sums.url, "application/octet-stream").use { r ->
                if (r.status !in 200..299) throw ToolInstallException("Checksum file answered ${r.status}")
                r.body.readBytes().decodeToString()
            }
            hashes.add(checksumFor(text, release.asset.name)
                ?: throw ToolInstallException("${sums.name} does not list ${release.asset.name}"))
        }
        return hashes
    }

    private fun fetch(
        asset: ReleaseAsset, http: ToolHttp, file: File, cancelled: AtomicBoolean, progress: (Long, Long?) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        http.get(asset.url, "application/octet-stream").use { r ->
            if (r.status !in 200..299) throw ToolInstallException("Download answered ${r.status}")
            val total = r.length ?: asset.size.takeIf { it > 0 }
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    if (cancelled.get()) throw ToolInstallException("cancelled")
                    val n = r.body.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    done += n
                    progress(done, total)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Copies the zip entry whose name ends with [entry] to [target]. */
    internal fun unzipEntry(zip: File, entry: String, target: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val name = e.name.replace('\\', '/')
                if (!e.isDirectory && (name == entry || name.endsWith("/$entry"))) {
                    target.outputStream().use { zin.copyTo(it) }
                    return
                }
            }
        }
        throw ToolInstallException("${zip.name} has no $entry")
    }

    private fun removeQuarantine(file: File) {
        runCatching {
            val p = ProcessBuilder("/usr/bin/xattr", "-d", "com.apple.quarantine", file.absolutePath)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly()
            // Exit 1 just means there was no such attribute.
        }.onFailure { Logger.info("ytdlp", "xattr failed for $file: $it") }
    }

    private fun checkStarts(tool: ExternalTool, file: File): Boolean =
        ExternalTools.version(FoundTool(tool, file.absolutePath, ToolOrigin.MANAGED)) != null
}

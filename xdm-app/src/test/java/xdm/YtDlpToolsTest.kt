package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.OS
import xdm.app.ytdlp.Arch
import xdm.app.ytdlp.ExternalTool
import xdm.app.ytdlp.ExternalTools
import xdm.app.ytdlp.GithubReleases
import xdm.app.ytdlp.ToolHttp
import xdm.app.ytdlp.ToolInstallException
import xdm.app.ytdlp.ToolInstaller
import xdm.app.ytdlp.ToolOrigin
import xdm.app.ytdlp.ToolSources
import xdm.app.ytdlp.checksumFor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Video download tools: where they are looked for, where they come from, how they are installed. */
class YtDlpToolsTest {
    private lateinit var dir: File

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("xdm-tools").toFile()
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun touch(f: File) = f.apply { parentFile.mkdirs(); writeText("x") }

    @Test
    fun lookupOrderIsCustomThenToolsFolderThenSystem() {
        val tools = File(dir, "tools")
        val sys = File(dir, "bin")
        touch(File(sys, "yt-dlp"))
        val existing = { f: File -> f.isFile }
        fun find(custom: String) =
            ExternalTools.lookup(ExternalTool.YT_DLP, custom, tools, OS.Linux, sys.path, dir.path, { null }, existing)

        assertEquals(ToolOrigin.SYSTEM, find("")?.origin)
        touch(File(tools, "yt-dlp"))
        assertEquals(ToolOrigin.MANAGED, find("")?.origin)
        val own = touch(File(dir, "own/yt-dlp"))
        assertEquals(ToolOrigin.CUSTOM, find(own.path)?.origin)
        assertNull(find(File(dir, "gone").path), "a custom path that stopped working is reported, not replaced")
        assertEquals("yt-dlp.exe", ExternalTool.YT_DLP.exeName(OS.Windows))
    }

    @Test
    fun versionsAreReadFromEachToolsOutput() {
        assertEquals("2026.10.26", ExternalTools.parseVersion(ExternalTool.YT_DLP, "2026.10.26\n"))
        assertEquals("2.9.8", ExternalTools.parseVersion(ExternalTool.DENO, "deno 2.9.8 (stable, release, aarch64-apple-darwin)\nv8 14"))
    }

    @Test
    fun everyPlatformHasAnAsset() {
        for (os in OS.entries) for (arch in Arch.entries) for (tool in ExternalTool.entries) {
            assertTrue(ToolSources.of(tool, os, arch) != null, "$tool on $os $arch")
        }
        assertEquals("yt-dlp_macos", ToolSources.of(ExternalTool.YT_DLP, OS.MacOS, Arch.ARM64)!!.asset)
        assertEquals("deno-aarch64-pc-windows-msvc.zip", ToolSources.of(ExternalTool.DENO, OS.Windows, Arch.ARM64)!!.asset)
        assertEquals("https://api.github.com/repos/yt-dlp/yt-dlp/releases?per_page=5",
            ToolSources.releasesUrl(ToolSources.of(ExternalTool.YT_DLP, OS.Linux, Arch.X64)!!))
    }

    @Test
    fun picksNewestStableReleaseThatHasTheAsset() {
        val body = """[
          {"tag_name":"2026.11.09","draft":false,"prerelease":true,"published_at":"2026-11-09T00:00:00Z",
           "assets":[{"name":"yt-dlp_macos","browser_download_url":"https://g/pre","size":1,"digest":"sha256:AA"}]},
          {"tag_name":"2026.11.07","draft":false,"prerelease":false,"published_at":"2026-11-07T00:00:00Z",
           "assets":[{"name":"yt-dlp_linux","browser_download_url":"https://g/l","size":1}]},
          {"tag_name":"2026.06.29","draft":false,"prerelease":false,"published_at":"2026-06-29T00:00:00Z",
           "assets":[{"name":"yt-dlp_macos","browser_download_url":"https://g/m","size":42,"digest":"sha256:ABCDEF"}]}]"""
        val source = ToolSources.of(ExternalTool.YT_DLP, OS.MacOS, Arch.X64)!!
        val picked = GithubReleases.pick(source, GithubReleases.parse(body))!!
        assertEquals("2026.06.29", picked.release.tag)
        assertEquals("abcdef", picked.asset.sha256)
        assertEquals("https://g/m", picked.asset.url)
    }

    @Test
    fun checksumFilesInTheirUsualForms() {
        val hex = "a".repeat(64)
        assertEquals(hex, checksumFor("${"b".repeat(64)}  yt-dlp.exe\n$hex  yt-dlp_macos\n", "yt-dlp_macos"))
        assertEquals(hex, checksumFor("$hex *bin/ffmpeg-master-latest-winarm64-gpl.zip", "ffmpeg-master-latest-winarm64-gpl.zip"))
        assertEquals(hex, checksumFor("${hex.uppercase()}\n", "deno-x86_64-apple-darwin.zip"), "a file holding only the hash")
        assertNull(checksumFor("${"b".repeat(64)}  other\n${"c".repeat(64)}  more\n", "mine"))
    }

    // ---- installing ---------------------------------------------------------------------

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private class FakeHttp(val files: Map<String, ByteArray>) : ToolHttp {
        override fun get(url: String, accept: String): ToolHttp.Response {
            val body = files[url] ?: return ToolHttp.Response(404, null, ByteArrayInputStream(ByteArray(0)))
            return ToolHttp.Response(200, body.size.toLong(), ByteArrayInputStream(body))
        }
    }

    private fun releaseJson(asset: String, url: String, digest: String?, extra: String = "") =
        """[{"tag_name":"v1","published_at":"2026-11-01T00:00:00Z","assets":[
           {"name":"$asset","browser_download_url":"$url","size":10${digest?.let { ",\"digest\":\"sha256:$it\"" } ?: ""}}$extra]}]"""

    @Test
    fun installsVerifiedBinaryAndRecordsIt() {
        val bin = "#!/bin/sh\necho 2026.10.26\n".toByteArray()
        val api = "https://api.test/repos/yt-dlp/yt-dlp/releases?per_page=5"
        val sums = "${sha(bin)}  yt-dlp_linux\n".toByteArray()
        val http = FakeHttp(mapOf(
            api to releaseJson("yt-dlp_linux", "https://dl/y", sha(bin),
                """,{"name":"SHA2-256SUMS","browser_download_url":"https://dl/sums","size":1}""").toByteArray(),
            "https://dl/y" to bin, "https://dl/sums" to sums,
        ))
        val release = ToolInstaller.latest(ExternalTool.YT_DLP, http, OS.Linux, Arch.X64, "https://api.test")
        assertTrue(ToolInstaller.isNewer(null, release))
        ToolInstaller.install(release, ExternalTool.YT_DLP, http, AtomicBoolean(), { _, _ -> }, dir, OS.Linux) { true }
        val installed = File(dir, "yt-dlp")
        assertEquals(bin.decodeToString(), installed.readText())
        assertTrue(installed.canExecute())
        val recorded = ToolInstaller.installed(dir)[ExternalTool.YT_DLP]!!
        assertEquals("v1", recorded.tag)
        assertEquals(sha(bin), recorded.sha256)
        assertFalse(ToolInstaller.isNewer(recorded, release), "same digest: nothing to update")
        assertFalse(File(dir, ".tmp").listFiles()!!.any(), "nothing left in the temp folder")
    }

    @Test
    fun mismatchKeepsTheOldBinary() {
        touch(File(dir, "yt-dlp")).writeText("old")
        val http = FakeHttp(mapOf(
            "https://api.test/repos/yt-dlp/yt-dlp/releases?per_page=5" to
                releaseJson("yt-dlp_linux", "https://dl/f", "0".repeat(64)).toByteArray(),
            "https://dl/f" to "tampered".toByteArray(),
        ))
        val release = ToolInstaller.latest(ExternalTool.YT_DLP, http, OS.Linux, Arch.X64, "https://api.test")
        val e = assertThrows(ToolInstallException::class.java) {
            ToolInstaller.install(release, ExternalTool.YT_DLP, http, AtomicBoolean(), { _, _ -> }, dir, OS.Linux) { true }
        }
        assertTrue(e.message!!.contains("Checksum"), e.message)
        assertEquals("old", File(dir, "yt-dlp").readText())
    }

    @Test
    fun assetWithoutAnyChecksumIsRefused() {
        val http = FakeHttp(mapOf(
            "https://api.test/repos/yt-dlp/yt-dlp/releases?per_page=5" to
                releaseJson("yt-dlp_linux", "https://dl/f", null).toByteArray(),
            "https://dl/f" to "bin".toByteArray(),
        ))
        val release = ToolInstaller.latest(ExternalTool.YT_DLP, http, OS.Linux, Arch.X64, "https://api.test")
        assertThrows(ToolInstallException::class.java) {
            ToolInstaller.install(release, ExternalTool.YT_DLP, http, AtomicBoolean(), { _, _ -> }, dir, OS.Linux) { true }
        }
        assertFalse(File(dir, "yt-dlp").exists())
    }

    @Test
    fun takesTheBinaryOutOfAZip() {
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("README")); z.write("r".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("deno")); z.write("deno-bin".toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        val http = FakeHttp(mapOf(
            "https://api.test/repos/denoland/deno/releases?per_page=5" to
                releaseJson("deno-x86_64-unknown-linux-gnu.zip", "https://dl/d", sha(zip)).toByteArray(),
            "https://dl/d" to zip,
        ))
        val release = ToolInstaller.latest(ExternalTool.DENO, http, OS.Linux, Arch.X64, "https://api.test")
        ToolInstaller.install(release, ExternalTool.DENO, http, AtomicBoolean(), { _, _ -> }, dir, OS.Linux) { true }
        assertEquals("deno-bin", File(dir, "deno").readText())
    }

    @Test
    fun aToolThatDoesNotStartIsNotInstalled() {
        val bin = "x".toByteArray()
        val http = FakeHttp(mapOf(
            "https://api.test/repos/yt-dlp/yt-dlp/releases?per_page=5" to releaseJson("yt-dlp_linux", "https://dl/y", sha(bin)).toByteArray(),
            "https://dl/y" to bin,
        ))
        val release = ToolInstaller.latest(ExternalTool.YT_DLP, http, OS.Linux, Arch.X64, "https://api.test")
        assertThrows(ToolInstallException::class.java) {
            ToolInstaller.install(release, ExternalTool.YT_DLP, http, AtomicBoolean(), { _, _ -> }, dir, OS.Linux) { false }
        }
        assertFalse(File(dir, "yt-dlp").exists())
        assertTrue(ToolInstaller.installed(dir).isEmpty())
    }

    @Test
    fun noUpdateWhileAToolRuns() {
        val bin = "x".toByteArray()
        val http = FakeHttp(mapOf(
            "https://api.test/repos/yt-dlp/yt-dlp/releases?per_page=5" to releaseJson("yt-dlp_linux", "https://dl/y", sha(bin)).toByteArray(),
            "https://dl/y" to bin,
        ))
        val release = ToolInstaller.latest(ExternalTool.YT_DLP, http, OS.Linux, Arch.X64, "https://api.test")
        val running = ToolInstaller.inUse.readLock()
        // Held by another thread, as a merge or lookup would be.
        val holder = Thread { running.lock(); Thread.sleep(2000); running.unlock() }.apply { start() }
        Thread.sleep(200)
        try {
            val e = assertThrows(ToolInstallException::class.java) {
                ToolInstaller.install(release, ExternalTool.YT_DLP, http, AtomicBoolean(), { _, _ -> }, dir, OS.Linux) { true }
            }
            assertEquals("busy", e.message)
        } finally {
            holder.join()
        }
    }
}

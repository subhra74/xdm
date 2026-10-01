package xdm.app.utils

import xdm.app.OS
import xdm.core.util.Logger
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.UserDefinedFileAttributeView
import java.util.concurrent.TimeUnit

/** Where a finished download came from: the URL fetched and the page that led to it. */
data class DownloadSource(val url: String?, val referrer: String?)

/**
 * Marks a finished download as coming from the internet, the way a browser does. A download the
 * extension takes over is never written by the browser, so without this the OS checks that key off
 * the mark (Gatekeeper, SmartScreen, Office Protected View) would not apply. See QUARANTINE.md.
 *
 * - Windows: the `Zone.Identifier` alternate data stream, ZoneId 3 (internet).
 * - macOS: the `com.apple.quarantine` attribute, written by `/usr/bin/xattr`. Not through
 *   [UserDefinedFileAttributeView]: the JDK prefixes the name with `user.` there, which Gatekeeper ignores.
 * - Linux: the `user.xdg.origin.url`/`user.xdg.referrer.url` attributes Chrome writes (no security effect).
 *
 * Failing to mark never fails the download: the file system may not support the mark at all
 * (FAT/exFAT, some network shares).
 */
object OriginMarker {

    private const val XATTR = "/usr/bin/xattr"
    private const val XATTR_TIMEOUT_SECONDS = 10L

    /** Writes the mark for [os]. Blocking; never throws. Returns false if the mark could not be written. */
    fun mark(file: File, source: DownloadSource, os: OS = detectOS()): Boolean {
        val clean = DownloadSource(cleanUrl(source.url), cleanUrl(source.referrer))
        return try {
            when (os) {
                OS.Windows -> markWindows(file, clean)
                OS.MacOS -> markMac(file)
                OS.Linux -> markLinux(file, clean)
            }
        } catch (e: Exception) {
            Logger.info("OriginMarker", "Could not mark $file as downloaded: $e")
            false
        }
    }

    /**
     * Keeps only http/https URLs, drops any `user:pass@` part and removes line breaks, so a URL can
     * neither leak credentials into the mark nor add lines to the Zone.Identifier file. Null if
     * nothing usable is left.
     */
    internal fun cleanUrl(url: String?): String? {
        val text = url?.replace("\r", "")?.replace("\n", "")?.trim() ?: return null
        val schemeEnd = text.indexOf("://")
        if (schemeEnd < 0) return null
        val scheme = text.substring(0, schemeEnd)
        if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) return null
        val authorityStart = schemeEnd + 3
        val authorityEnd = text.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).let { if (it < 0) text.length else it }
        val at = text.lastIndexOf('@', authorityEnd - 1)
        if (at < authorityStart) return text
        return text.substring(0, authorityStart) + text.substring(at + 1)
    }

    /** The Zone.Identifier stream contents; URL lines are left out when there is no value. */
    internal fun zoneIdentifier(source: DownloadSource): String = buildString {
        append("[ZoneTransfer]\r\n")
        append("ZoneId=3\r\n")
        source.referrer?.let { append("ReferrerUrl=").append(it).append("\r\n") }
        source.url?.let { append("HostUrl=").append(it).append("\r\n") }
    }

    /** `flags;time;agent;` - no event UUID, as nothing is recorded in the quarantine events database. */
    internal fun quarantineValue(epochSeconds: Long): String = "0081;${java.lang.Long.toHexString(epochSeconds)};XDM;"

    // java.io (not NIO, whose Path rejects the ':') opens "file:stream" as an alternate data stream;
    // this relies on jdk.io.File.enableADS, which is true by default. Replaces any existing stream.
    private fun markWindows(file: File, source: DownloadSource): Boolean {
        FileOutputStream(file.absolutePath + ":Zone.Identifier").use {
            it.write(zoneIdentifier(source).toByteArray(StandardCharsets.UTF_8))
        }
        return true
    }

    private fun markMac(file: File): Boolean {
        val value = quarantineValue(System.currentTimeMillis() / 1000)
        // No shell: the file name reaches xattr as a single argument, whatever characters it holds.
        val process = ProcessBuilder(XATTR, "-w", "com.apple.quarantine", value, file.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (!process.waitFor(XATTR_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            Logger.info("OriginMarker", "xattr timed out for $file")
            return false
        }
        if (process.exitValue() != 0) {
            val error = process.errorStream.readBytes().toString(Charsets.UTF_8).trim()
            Logger.info("OriginMarker", "xattr failed (${process.exitValue()}) for $file: $error")
            return false
        }
        return true
    }

    // The JDK adds the "user." namespace prefix on Linux, which is where these attributes belong.
    private fun markLinux(file: File, source: DownloadSource): Boolean {
        if (source.url == null && source.referrer == null) return true
        val view = Files.getFileAttributeView(file.toPath(), UserDefinedFileAttributeView::class.java)
            ?: return false
        source.url?.let { view.write("xdg.origin.url", StandardCharsets.UTF_8.encode(it)) }
        source.referrer?.let { view.write("xdg.referrer.url", StandardCharsets.UTF_8.encode(it)) }
        return true
    }
}

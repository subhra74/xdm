package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppConfig
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** The proxy password is kept in memory only; the user name is still saved, in a user-only file. */
class AppConfigProxyPasswordTest {
    private lateinit var dir: File

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("xdm-config").toFile()
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun anyFileContains(text: String): Boolean {
        val needle = utf(text)
        return dir.listFiles()!!.filter { it.isFile }.any { f ->
            val bytes = f.readBytes()
            (0..bytes.size - needle.size).any { i -> needle.indices.all { bytes[i + it] == needle[it] } }
        }
    }

    /** The modified-UTF-8 bytes `writeUTF` puts on disk, without the length prefix. */
    private fun utf(text: String) =
        ByteArrayOutputStream().also { DataOutputStream(it).writeUTF(text) }.toByteArray().drop(2).toByteArray()

    @Test
    fun password_isNotSaved() {
        AppConfig(dir.absolutePath).apply { proxyUser = "alice"; proxyPass = "hunter2-secret" }.save()

        assertFalse(anyFileContains("hunter2-secret"), "proxy password written to disk")
        val loaded = AppConfig(dir.absolutePath).apply { load() }
        assertEquals("alice", loaded.proxyUser, "proxy user not saved")
        assertEquals("", loaded.proxyPass, "proxy password came back from disk")
    }

    @Test
    fun oldFormatConfig_isDeletedWithItsBackups() {
        // A config from a build before the tagged format, which may carry the proxy password.
        val pass = utf("hunter2-secret")
        val old = byteArrayOf(0, 1, 0, 1) + byteArrayOf(0, pass.size.toByte()) + pass +
            "XDM-END!".toByteArray(Charsets.US_ASCII)
        for (name in listOf(AppConfig.CONFIG_FILE, "${AppConfig.CONFIG_FILE}.bak2")) File(dir, name).writeBytes(old)

        val loaded = AppConfig(dir.absolutePath).apply { load() }

        assertEquals("", loaded.proxyPass, "old config was read")
        assertFalse(anyFileContains("hunter2-secret"), "old config left on disk (incl. backups)")
    }

    @Test
    fun savedConfig_isOwnerOnly() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX only")
        AppConfig(dir.absolutePath).save()
        val perms = Files.getPosixFilePermissions(File(dir, AppConfig.CONFIG_FILE).toPath())
        assertEquals("rw-------", PosixFilePermissions.toString(perms), "config readable by others")
    }
}

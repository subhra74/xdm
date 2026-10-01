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
    fun legacyConfigWithPassword_isScrubbedButKeptForTheRun() {
        AppConfig(dir.absolutePath).apply { proxyUser = "alice" }.save()
        // Put a password into the proxyPass slot the way older builds wrote it.
        val file = File(dir, AppConfig.CONFIG_FILE)
        val bytes = file.readBytes()
        val user = utf("alice")
        val at = (0..bytes.size - user.size).first { i -> user.indices.all { bytes[i + it] == user[it] } } + user.size
        val pass = utf("hunter2-secret")
        file.writeBytes(
            bytes.copyOfRange(0, at) + byteArrayOf(0, pass.size.toByte()) + pass + bytes.copyOfRange(at + 2, bytes.size)
        )
        assertEquals("hunter2-secret", AppConfig(dir.absolutePath).apply { load() }.proxyPass, "test setup")

        assertFalse(anyFileContains("hunter2-secret"), "legacy proxy password left on disk (incl. backups)")
    }

    @Test
    fun savedConfig_isOwnerOnly() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX only")
        AppConfig(dir.absolutePath).save()
        val perms = Files.getPosixFilePermissions(File(dir, AppConfig.CONFIG_FILE).toPath())
        assertEquals("rw-------", PosixFilePermissions.toString(perms), "config readable by others")
    }
}

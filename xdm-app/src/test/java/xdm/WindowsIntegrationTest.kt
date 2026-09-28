package xdm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import xdm.app.OS
import xdm.app.utils.KeepAwake
import xdm.app.utils.detectOS
import xdm.app.utils.win.Win32Power
import xdm.app.utils.win.Win32Registry

/**
 * The java.lang.foreign Windows layer: registry writes (the login entry and the `xdm-app://`
 * handler are built on these) and the sleep inhibitor.
 *
 * Windows only - skipped elsewhere. The platform-independent plumbing underneath is covered by
 * [FfmDowncallTest], which runs everywhere.
 */
class WindowsIntegrationTest {

    private val scratchKey = "Software\\XDM-Test-${ProcessHandle.current().pid()}"

    @Before
    fun onlyOnWindows() {
        assumeTrue("Windows only", detectOS() == OS.Windows)
    }

    @After
    fun cleanUp() {
        if (detectOS() == OS.Windows) {
            Win32Registry.deleteTree(scratchKey)
        }
        KeepAwake.release()
    }

    @Test
    fun stringValuesRoundTrip() {
        // A path with spaces and backslashes plus a quoted argument: exactly the shape that made
        // the old .reg-file approach fragile.
        val value = """"C:\Program Files\Xtreme Download Manager\xdm-app.exe" --minimized"""
        assertTrue(Win32Registry.setString(scratchKey, "Command", value))
        assertEquals(value, Win32Registry.getString(scratchKey, "Command"))
    }

    @Test
    fun defaultValueRoundTrips() {
        assertTrue(Win32Registry.setString(scratchKey, "", "URL:Xtreme Download Manager Protocol"))
        assertEquals("URL:Xtreme Download Manager Protocol", Win32Registry.getString(scratchKey, ""))
    }

    @Test
    fun missingKeysAndValuesReadAsNull() {
        assertNull(Win32Registry.getString("$scratchKey\\nope", "Command"))
        assertTrue(Win32Registry.setString(scratchKey, "Present", "x"))
        assertNull(Win32Registry.getString(scratchKey, "Absent"))
    }

    @Test
    fun deletingIsIdempotent() {
        assertTrue(Win32Registry.setString(scratchKey, "Command", "x"))
        assertTrue(Win32Registry.deleteValue(scratchKey, "Command"))
        assertNull(Win32Registry.getString(scratchKey, "Command"))
        assertTrue("deleting an absent value is not a failure", Win32Registry.deleteValue(scratchKey, "Command"))
        assertTrue(Win32Registry.deleteTree(scratchKey))
        assertTrue("deleting an absent key is not a failure", Win32Registry.deleteTree(scratchKey))
    }

    @Test
    fun unicodeSurvivesTheRoundTrip() {
        val value = "C:\\Users\\Ünicode Pfad\\xdm-app.exe"
        assertTrue(Win32Registry.setString(scratchKey, "Path", value))
        assertEquals(value, Win32Registry.getString(scratchKey, "Path"))
    }

    @Test
    fun executionStateIsSetAndCleared() {
        val previous = Win32Power.setThreadExecutionState(
            Win32Power.ES_CONTINUOUS or Win32Power.ES_SYSTEM_REQUIRED
        )
        assertTrue("SetThreadExecutionState reported failure", previous != 0)
        assertTrue(Win32Power.setThreadExecutionState(Win32Power.ES_CONTINUOUS) != 0)
    }

    /** The inhibitor thread is the inhibitor: no download must mean no thread. */
    @Test
    fun keepAwakeThreadStartsAndIsGoneAfterRelease() {
        KeepAwake.acquire()
        assertNotNull("inhibitor thread was not started", keepAwakeThread())

        KeepAwake.acquire() // idempotent: still exactly one
        assertEquals(1, Thread.getAllStackTraces().keys.count { it.name == KEEP_AWAKE_THREAD })

        KeepAwake.release()
        assertFalse("inhibitor thread outlived the download", keepAwakeThread()?.isAlive ?: false)
    }

    private fun keepAwakeThread(): Thread? =
        Thread.getAllStackTraces().keys.firstOrNull { it.name == KEEP_AWAKE_THREAD }

    private companion object {
        const val KEEP_AWAKE_THREAD = "xdm-keep-awake"
    }
}

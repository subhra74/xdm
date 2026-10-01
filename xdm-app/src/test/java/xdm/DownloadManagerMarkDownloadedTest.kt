package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.AppContext
import xdm.app.DownloadCompleteNotification
import xdm.app.IAppInstance
import xdm.app.IPlatformInvoke
import xdm.app.RecordStatus
import xdm.app.utils.DownloadSource
import xdm.core.downloaders.DownloadStatusInfo
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/** A finished download is marked as coming from the internet before anything can open it. */
class DownloadManagerMarkDownloadedTest : DownloadManagerTestBase() {

    private val events = CopyOnWriteArrayList<String>()
    private val marked = CopyOnWriteArrayList<Pair<File, DownloadSource>>()
    private var markResult = true

    @BeforeEach
    fun installFakes() {
        config.downloadCompleteNotification = DownloadCompleteNotification.DIALOG
        config.runCommand = true
        AppContext.app = Proxy.newProxyInstance(
            IAppInstance::class.java.classLoader, arrayOf(IAppInstance::class.java)
        ) { _, method, _ ->
            if (method.name == "showDownloadCompleteWindow") events.add("dialog")
            null
        } as IAppInstance
        AppContext.platform = object : IPlatformInvoke {
            override fun runVirusScan(file: String) {}
            override fun runCustomCommand(file: String) {
                events.add("command")
            }

            override fun shutdownPC() {}
            override fun markDownloadedFile(file: File, source: DownloadSource): Boolean {
                events.add("mark")
                marked.add(file to source)
                return markResult
            }
        }
    }

    /** Starts an HTTP download, then reports it finished with a real file in place. */
    private fun finish(origin: String? = "https://example.com/page"): Pair<Long, File> {
        val task = httpTask().copy(origin = origin)
        dm.startHttpDownload(task)
        remember()
        val file = File(dir, task.fileName).apply { writeText("ok") }
        host().onDownloadSuccess(DownloadStatusInfo.FinalInfo(task.id, 2, file.name, dir.absolutePath))
        return task.id to file
    }

    @Test
    fun marksBeforeDialogAndPostDownloadCommand() {
        val (id, file) = finish()

        assertEquals(listOf("mark", "dialog", "command"), events, "marking must come first")
        assertEquals(file, marked.single().first)
        assertEquals(DownloadSource("$hangingBase/file", "https://example.com/page"), marked.single().second)
        assertEquals(RecordStatus.FINISHED, appDB.getById(id)?.status)
    }

    @Test
    fun skippedWhenTurnedOff() {
        config.markDownloadedFiles = false
        finish()

        assertTrue(marked.isEmpty(), "nothing marked when the setting is off")
        assertEquals(listOf("dialog", "command"), events)
    }

    @Test
    fun failedMark_stillFinishesTheDownload() {
        markResult = false
        val (id, _) = finish(origin = null)

        assertEquals(DownloadSource("$hangingBase/file", null), marked.single().second)
        assertEquals(RecordStatus.FINISHED, appDB.getById(id)?.status)
        assertEquals(listOf("mark", "dialog", "command"), events)
    }
}

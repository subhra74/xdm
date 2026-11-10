package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.capture.CapturedSubtitle
import xdm.capture.StreamingVideoDisplayInfo
import xdm.core.downloaders.CommitResult
import xdm.core.downloaders.DownloadType
import xdm.core.downloaders.partFileName
import xdm.core.media.subtitle.Subtitles
import xdm.core.storage.LocalStorage
import java.io.File

/** A captured subtitle file: downloaded as a plain HTTP file, converted to SRT when it is published. */
class DownloadManagerSubtitleTest : DownloadManagerTestBase() {
    private val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHällo\n"

    private fun captured(format: Subtitles.Format = Subtitles.Format.VTT) = CapturedSubtitle(
        httpTask().copy(fileName = "show.en.${format.ext}", maxPiece = 1),
        StreamingVideoDisplayInfo(), format, "en",
    )

    private fun publish(task: xdm.core.downloaders.HttpDownloadTaskInfo, content: ByteArray): CommitResult {
        val out = File(task.targetFolder).apply { mkdirs() }
        taskDB.saveHttpTask(task)
        File(out, partFileName(task.id)).writeBytes(content)
        return host().commitOutputFile(task.id, LocalStorage.file(File(out, partFileName(task.id)).absolutePath), DownloadType.Http)
    }

    @Test
    fun pickedSubtitle_isAnHttpDownloadThatRemembersSaveAsSrt() {
        val sub = captured()
        tracker.addSubtitle(sub)
        val id = dm.addVideoDownload(sub.task.id, "show.en.srt", null, File(dir, "out").absolutePath, 8, null, true)!!
        remember()
        val task = taskDB.getHttpTask(id)!!
        assertTrue(task.convertToSrt)
        assertEquals("show.en.srt", task.fileName)
        assertEquals(1, task.maxPiece, "a subtitle keeps one connection")
    }

    @Test
    fun srtCapture_isNeverMarkedForConversion() {
        val sub = captured(Subtitles.Format.SRT)
        tracker.addSubtitle(sub)
        val id = dm.addVideoDownload(sub.task.id, "show.en.srt", null, File(dir, "out").absolutePath, convertToSrt = true)!!
        remember()
        assertFalse(taskDB.getHttpTask(id)!!.convertToSrt)
    }

    @Test
    fun publish_convertsVttToSrtWithBom() {
        val task = httpTask().copy(targetFolder = File(dir, "out").absolutePath, fileName = "show.en.vtt", convertToSrt = true)
        val result = publish(task, vtt.toByteArray())
        assertEquals("show.en.srt", (result as CommitResult.Success).fileName)
        val bytes = File(dir, "out/show.en.srt").readBytes()
        assertEquals(Subtitles.srtBytes("1\r\n00:00:01,000 --> 00:00:02,000\r\nHällo\r\n\r\n").toList(), bytes.toList())
        assertFalse(File(dir, "out/show.en.vtt").exists())
        assertEquals("show.en.srt", taskDB.getHttpTask(task.id)!!.fileName)
    }

    @Test
    fun publish_notASubtitle_keepsTheFileAsDownloaded() {
        val task = httpTask().copy(targetFolder = File(dir, "out").absolutePath, fileName = "show.en.vtt", convertToSrt = true)
        val result = publish(task, "<html>expired</html>".toByteArray())
        assertEquals("show.en.vtt", (result as CommitResult.Success).fileName)
        assertEquals("<html>expired</html>", File(dir, "out/show.en.vtt").readText())
    }

    @Test
    fun publish_alreadySrt_onlyTheNameChanges() {
        // A publish that failed after converting is retried on resume with the SRT already in the part file.
        val task = httpTask().copy(targetFolder = File(dir, "out").absolutePath, fileName = "show.en.vtt", convertToSrt = true)
        val srt = "1\r\n00:00:01,000 --> 00:00:02,000\r\nHi\r\n\r\n"
        val result = publish(task, srt.toByteArray())
        assertEquals("show.en.srt", (result as CommitResult.Success).fileName)
        assertEquals(srt, File(dir, "out/show.en.srt").readText())
    }
}

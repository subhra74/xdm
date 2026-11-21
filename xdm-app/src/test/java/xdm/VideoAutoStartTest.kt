package xdm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xdm.app.VideoDownloadDefaults
import xdm.capture.StreamingVideoDisplayInfo
import xdm.core.downloaders.SubtitleTrack
import xdm.manager.DownloadCategory
import java.io.File

/** "Start download immediately" for captured videos: the dialog's preselection, without the dialog. */
class VideoAutoStartTest : DownloadManagerTestBase() {

    private fun onlyRecordId(): Long {
        assertEquals(1, appDB.size, "records")
        return appDB.getByIndex(0).id
    }

    @Test
    fun hlsCapture_startsInTheRuleCategoryWithTheDefaultSubtitle() {
        config.rememberNone = false
        val tracks = listOf(
            SubtitleTrack("Forced", "zz", forced = true),
            SubtitleTrack("Main", "zz-x", isDefault = true),
        )
        val source = hlsTask().copy(fileName = "clip.mp4", subtitles = tracks)
        tracker.addVideoHls(listOf(source to StreamingVideoDisplayInfo()))

        assertTrue(VideoDownloadDefaults.startWithoutDialog(source.id, "clip.mp4"), "started")
        remember()

        val task = taskDB.getHlsTask(onlyRecordId())!!
        val video = DownloadCategory.defaults(dir.absolutePath).first { it.id == "CAT_VIDEOS" }
        assertEquals(video.id, task.categoryId, "category from the rules")
        assertEquals(video.folder, task.targetFolder, "the category's folder")
        assertEquals(config.maxSegments, task.maxPiece, "the configured segment count")
        assertEquals(listOf(tracks[1]), task.subtitles, "the manifest's default track")
    }

    @Test
    fun rememberedNone_startsInTheNoneFolder() {
        config.rememberNone = true
        val none = File(dir, "picked").absolutePath
        config.noneFolder = none
        val source = httpTask().apply { fileName = "clip.mp4" }
        tracker.addVideoHttp(listOf(source to StreamingVideoDisplayInfo()))

        assertTrue(VideoDownloadDefaults.startWithoutDialog(source.id, "a/b:clip.mp4"), "started")
        remember()

        val task = taskDB.getHttpTask(onlyRecordId())!!
        assertNull(task.categoryId, "None")
        assertEquals(none, task.targetFolder, "the None folder")
        assertFalse(task.fileName.contains('/'), "sanitized name: ${task.fileName}")
    }

    @Test
    fun unknownVideo_startsNothing() {
        assertFalse(VideoDownloadDefaults.startWithoutDialog(9999, "clip.mp4"), "nothing to start")
        assertEquals(0, appDB.size, "records")
    }

    @Test
    fun defaultSubtitle_prefersSystemLanguageAndFullTracks() {
        val forced = SubtitleTrack("en forced", "en", forced = true)
        val full = SubtitleTrack("en", "en")
        val def = SubtitleTrack("de", "de", isDefault = true)
        assertEquals(full, VideoDownloadDefaults.defaultSubtitle(listOf(forced, full, def), "en"), "full track first")
        assertEquals(def, VideoDownloadDefaults.defaultSubtitle(listOf(forced, full, def), "fr"), "manifest default")
        assertNull(VideoDownloadDefaults.defaultSubtitle(listOf(forced, full), "fr"), "none")
    }

    @Test
    fun srtName_swapsOnlyTheSubtitleExtension() {
        assertEquals("a.srt", VideoDownloadDefaults.srtName("a.vtt", "vtt", toSrt = true))
        assertEquals("a.vtt", VideoDownloadDefaults.srtName("a.SRT", "vtt", toSrt = false))
        assertEquals("a.txt", VideoDownloadDefaults.srtName("a.txt", "vtt", toSrt = true), "other extension kept")
    }
}

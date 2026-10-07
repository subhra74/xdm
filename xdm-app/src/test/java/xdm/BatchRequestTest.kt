package xdm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xdm.app.utils.BatchFolderState
import xdm.app.utils.batchFolderState
import xdm.app.utils.defaultBatchName
import xdm.app.utils.sanitizeBatchName
import xdm.app.utils.suggestBatchName
import xdm.app.utils.uniqueFileNames
import xdm.integration.BatchMessage
import xdm.integration.BatchMessageGroup
import xdm.integration.BatchMessageItem
import xdm.integration.MAX_BATCH_ITEMS
import xdm.integration.toBatchRequest
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Locale

/** The `/batch` message as the dialog sees it, and the batch name / folder rules. */
class BatchRequestTest {
    private lateinit var dir: File

    @BeforeEach
    fun setup() {
        dir = Files.createTempDirectory("xdm-batch-req").toFile()
    }

    @AfterEach
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun message_keepsHttpLinksOnceWithTheirCookieGroup() {
        val msg = BatchMessage(
            tabUrl = "https://example.com/gallery/", tabTitle = "Gallery", userAgent = "UA",
            referer = "https://example.com/gallery/",
            groups = listOf(
                BatchMessageGroup("c=1", listOf(BatchMessageItem("https://cdn.example.com/a.jpg", fileSize = 10))),
                BatchMessageGroup(
                    "c=2", listOf(
                        BatchMessageItem("https://example.com/f.pdf", filename = "report.pdf"),
                        BatchMessageItem("ftp://example.com/x"),
                        BatchMessageItem("https://cdn.example.com/a.jpg"),
                    )
                ),
                BatchMessageGroup(null, listOf(BatchMessageItem("http://mirror.example.org/z.zip"))),
            ),
        )

        val req = toBatchRequest(msg)!!

        assertEquals(listOf("https://cdn.example.com/a.jpg", "https://example.com/f.pdf", "http://mirror.example.org/z.zip"),
            req.items.map { it.url }, "http(s) only, each link once")
        assertEquals(listOf("c=1", "c=2"), req.cookies, "one entry per cookie string")
        assertEquals(listOf(0, 1, -1), req.items.map { it.cookieGroup }, "items point at their cookie")
        assertEquals("report.pdf", req.items[1].fileName, "page's name kept")
        assertEquals(10L, req.items[0].knownSize, "size kept")
        assertEquals(listOf("UA"), req.headers?.get("User-Agent"), "user agent sent with every link")
        assertEquals(listOf("https://example.com/gallery/"), req.headers?.get("Referer"), "referer too")
        assertTrue(req.fromBrowser, "marked as from the browser")
    }

    @Test
    fun message_isCappedAndEmptyMeansNothing() {
        val many = (0 until MAX_BATCH_ITEMS + 10).map { BatchMessageItem("https://x.example/$it") }
        assertEquals(MAX_BATCH_ITEMS, toBatchRequest(BatchMessage(groups = listOf(BatchMessageGroup(items = many))))!!.items.size,
            "at most $MAX_BATCH_ITEMS links")
        assertNull(toBatchRequest(BatchMessage(groups = listOf(BatchMessageGroup(items = listOf(BatchMessageItem("data:x")))))),
            "no usable link, no request")
    }

    @Test
    fun name_comesFromTitleElseHostAndTime() {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).parse("2026-10-02 14:30")
        assertEquals("Gallery _ Example Oct-02-2026", defaultBatchName("Gallery | Example", null, now), "title made safe, date appended")
        val long = defaultBatchName("x".repeat(200), null, now)
        assertEquals(100, long.length, "long title shortened to the limit")
        assertTrue(long.endsWith(" Oct-02-2026"), "date kept when the title is shortened")
        assertEquals("example.com Oct-02-2026", defaultBatchName("  ", "https://www.example.com/p", now), "host and date")
        assertEquals("Batch Oct-02-2026", defaultBatchName(null, null, now), "no page at all")
        assertNull(sanitizeBatchName("   "), "blank is no name")
        assertEquals(100, sanitizeBatchName("x".repeat(300))!!.length, "capped")
    }

    @Test
    fun folder_missingOrEmptyIsUsable() {
        assertEquals(BatchFolderState.Ok, batchFolderState(File(dir, "new")), "missing")
        assertEquals(BatchFolderState.Ok, batchFolderState(File(dir, "empty").apply { mkdirs() }), "empty")
        File(dir, "full").mkdirs()
        File(dir, "full/x").writeText("x")
        assertEquals(BatchFolderState.NotEmpty, batchFolderState(File(dir, "full")), "has files")
        File(dir, "file").writeText("x")
        assertEquals(BatchFolderState.NotAFolder, batchFolderState(File(dir, "file")), "is a file")
        assertEquals("full (2)", suggestBatchName(dir, "full"), "next free name")
        assertEquals("new", suggestBatchName(dir, "new"), "already free")
    }

    @Test
    fun fileNames_areMadeUniqueIgnoringCase() {
        assertEquals(listOf("a.jpg", "A_1.jpg", "a_2.jpg", "b"), uniqueFileNames(listOf("a.jpg", "A.jpg", "a.jpg", "b")),
            "later duplicates get a suffix")
    }
}

// Copyright 2026 XDM contributors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
package xdm.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xdm.core.downloaders.DownloadError
import xdm.core.storage.LocalStorage
import xdm.core.storage.Storage
import xdm.core.storage.StorageFolder
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

class TestHttpServerTimestamp : HttpDownloadTestBase() {
    private val date = "Thu, 11 Jun 2026 14:24:01 GMT"
    private val millis = Instant.parse("2026-06-11T14:24:01Z").toEpochMilli()
    private fun config(enabled: Boolean = true) = TestConfig(4, getServerTime = enabled)

    @Test fun segmentedRedirect_preservesDateAfterCommitAndDoesNotChangeContent() {
        val bytes = randomData(2 * 1024 * 1024, 101)
        server.register("/original", Endpoint(bytes, lastModified = date, plan = {
            ConnPlan(throttleChunk = 64 * 1024, throttleSleepMs = 2)
        }))
        server.redirect("/redirect") { server.url("/original") }
        val h = host()
        newTask(101, "/redirect", h, config()).start()
        awaitSuccess(h)
        assertChecksumMatches(h, bytes)
        assertEquals("out-101.bin", h.finalFile!!.name)
        assertEquals(millis, h.finalFile!!.lastModified())
    }

    @Test fun disabled_keepsDownloadTime() {
        server.register("/disabled", Endpoint(randomData(1000, 102), lastModified = date))
        val h = host()
        newTask(102, "/disabled", h, config(false)).start()
        awaitSuccess(h)
        assertNotEquals(millis, h.finalFile!!.lastModified())
    }

    @Test fun missingOrMalformedDate_keepsDownloadTimeAndBytes() {
        for ((i, value) in listOf(null, "not a date").withIndex()) {
            val bytes = randomData(1000, 103L+i)
            server.register("/invalid-$i", Endpoint(bytes, lastModified = value))
            val h = host()
            newTask(103L+i, "/invalid-$i", h, config()).start()
            awaitSuccess(h)
            assertChecksumMatches(h, bytes)
            assertTrue(h.finalFile!!.lastModified() > millis)
        }
    }

    @Test fun unknownLength_preservesDate() {
        val bytes = randomData(300000, 105)
        server.register("/stream", Endpoint(bytes, resumeSupported = false, lastModified = date,
            plan = { ConnPlan(ignoreRange = true, omitContentLength = true) }))
        val h = host()
        newTask(105, "/stream", h, config()).start()
        awaitSuccess(h)
        assertChecksumMatches(h, bytes)
        assertEquals(millis, h.finalFile!!.lastModified())
    }

    @Test fun pauseAndNewTaskResume_preservesPersistedDate() {
        val bytes = randomData(2 * 1024 * 1024, 106)
        server.register("/resume", Endpoint(bytes, lastModified = date, plan = {
            ConnPlan(throttleChunk = 16 * 1024, throttleSleepMs = 15)
        }))
        val first = host()
        val task = newTask(106, "/resume", first, TestConfig(1, getServerTime = true))
        task.start()
        assertTrue(waitFor(10000) { tmpDir.listFiles()?.any { it.length() > 0 } == true })
        task.stop()
        assertTrue(first.pauseLatch.await(5, TimeUnit.SECONDS))
        assertNull(first.success)
        val second = host()
        newTask(106, "/resume", second, config()).resume()
        awaitSuccess(second)
        assertChecksumMatches(second, bytes)
        assertEquals(millis, second.finalFile!!.lastModified())
    }

    @Test fun retryPublishingCompletedDownload_restoresSavedDateWithoutNetwork() {
        val bytes = randomData(1000, 107)
        server.register("/publish", Endpoint(bytes, lastModified = date))
        val first = host().apply { commitError = DownloadError.OutputWriteError }
        newTask(107, "/publish", first, config()).start()
        awaitDone(first)
        assertNotNull(first.failure)
        server.stop()
        val second = host()
        newTask(107, "/publish", second, config()).resume()
        awaitSuccess(second)
        assertChecksumMatches(second, bytes)
        assertEquals(millis, second.finalFile!!.lastModified())
    }

    @Test fun unsupportedOrFailingTimestampStorage_doesNotFailDownload() {
        for (fails in listOf(false, true)) {
            val storage = object : Storage {
                override fun folder(location: String): StorageFolder {
                    val delegate = LocalStorage.folder(location)
                    return object : StorageFolder by delegate {
                        override fun setLastModified(name: String, millis: Long): Boolean {
                            if (fails) throw IOException("timestamp denied")
                            return false
                        }
                    }
                }
            }
            val id = if (fails) 109L else 108L
            val bytes = randomData(1000, id)
            server.register("/storage-$id", Endpoint(bytes, lastModified = date))
            val h = host(storage)
            newTask(id, "/storage-$id", h, config()).start()
            awaitSuccess(h)
            assertChecksumMatches(h, bytes)
        }
    }
}

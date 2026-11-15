// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.core.downloaders.web

import xdm.core.downloaders.web.http.ChunkStatus

/** One row of the progress window's table: a connection slot, its [status] and the [bytes] it has downloaded. */
data class SlotProgress(val status: ChunkStatus, val bytes: Long)

/**
 * The connection slots of one download session, numbered 1 to the most pieces that were live at once.
 * A piece that stops being live keeps its slot, shown finished (or failed), until a newly started piece
 * takes the slot over; so with 8 connections there are 8 slots to the end. A slot's bytes add up over all
 * the pieces that used it: the one there now plus everything the earlier ones downloaded.
 *
 * Small on purpose: one entry per slot (at most the segment count), plus the few pieces that lost their
 * slot while unfinished and may come back. Not thread-safe; [ProgressTracker] calls it under its lock.
 */
class ConnectionSlots {
    private class Slot(var id: Long, var status: ChunkStatus, var bytes: Long, var live: Boolean) {
        /** What earlier pieces in this slot downloaded. */
        var carried = 0L

        /** Bytes of the current piece already counted in another slot (a retried piece): not counted again. */
        var counted = 0L
        val total get() = carried + (bytes - counted).coerceAtLeast(0)
    }

    private val slots = ArrayList<Slot>()

    /**
     * Pieces that lost their slot unfinished (failed, or connecting when the slot was taken), with the bytes
     * their slot counted for them. A retry starts from those bytes, so only what comes after is new.
     */
    private val leftUnfinished = HashMap<Long, Long>()

    /** Follows the pieces in [segments], the [SegmentProgress.live] ones getting a slot. */
    fun update(segments: Collection<SegmentProgress>) {
        val byId = HashMap<Long, SegmentProgress>(segments.size * 2)
        for (s in segments) byId[s.id] = s
        for (slot in slots) {
            val s = byId[slot.id]
            if (s != null) slot.bytes = s.bytes
            slot.live = s?.live == true
            // Gone from the list (a part merged into its neighbour) counts as done too.
            slot.status = if (s == null || (!s.live && s.status != ChunkStatus.Failed)) ChunkStatus.Finished else s.status
        }
        val shown = HashSet<Long>()
        for (slot in slots) if (slot.live) shown.add(slot.id)
        for (s in segments) {
            if (!s.live || !shown.add(s.id)) continue
            val counted = leftUnfinished.remove(s.id) ?: 0L
            val free = slots.indexOfFirst { !it.live }
            if (free < 0) {
                slots += Slot(s.id, s.status, s.bytes, live = true).also { it.counted = counted }
                continue
            }
            val slot = slots[free]
            if (slot.status != ChunkStatus.Finished) leftUnfinished[slot.id] = slot.bytes
            slot.carried = slot.total
            slot.id = s.id
            slot.status = s.status
            slot.bytes = s.bytes
            slot.counted = counted
            slot.live = true
        }
    }

    /** All pieces are done: every slot shows finished, none live. A failed slot stays failed. */
    fun finish(segments: Collection<SegmentProgress>) {
        update(segments)
        for (slot in slots) {
            slot.live = false
            if (slot.status != ChunkStatus.Failed) slot.status = ChunkStatus.Finished
        }
    }

    /** A copy for the UI, in slot order. */
    fun snapshot(): List<SlotProgress> = slots.map { SlotProgress(it.status, it.total) }
}

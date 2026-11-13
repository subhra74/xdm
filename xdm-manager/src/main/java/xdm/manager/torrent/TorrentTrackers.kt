// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-XDM-Android-Official-Store-Builds
// Additional terms and the exception: xdm-android/NOTICE

package xdm.manager.torrent

import java.io.ByteArrayOutputStream
import java.net.URLEncoder

/**
 * The extra trackers the user adds to new public torrents (Settings → BitTorrent). Trackers live outside the
 * info dictionary, so adding them changes neither the info-hash nor the info dictionary's bytes: a .torrent file
 * is re-assembled around its original value bytes with a merged `announce-list`; a magnet link gets `tr=`.
 */
object TorrentTrackers {
    /** The settings text as tiers: one URL per line, a blank line starts the next tier. */
    fun tiers(text: String): List<List<String>> =
        text.split(Regex("\\r?\\n\\s*\\r?\\n")).map { block ->
            block.lines().map { it.trim() }.filter { it.isNotEmpty() && "://" in it }
        }.filter { it.isNotEmpty() }

    /** [magnet] with every tracker of [tiers] it does not have yet as `tr=`. */
    fun addToMagnet(magnet: String, tiers: List<List<String>>): String {
        val have = magnet.substringAfter('?', "").split('&')
            .filter { it.startsWith("tr=", ignoreCase = true) }
            .map { runCatching { java.net.URLDecoder.decode(it.substring(3), "UTF-8") }.getOrDefault(it) }.toSet()
        val add = tiers.flatten().distinct().filter { it !in have }
        if (add.isEmpty()) return magnet
        return magnet + add.joinToString("") { "&tr=" + URLEncoder.encode(it, "UTF-8") }
    }

    /**
     * [torrent] with [tiers] after its own trackers in `announce-list` (URLs it has already left out); null when
     * it is not a bencoded dictionary. The other top-level values, the info dictionary included, keep their bytes.
     */
    fun addToTorrent(torrent: ByteArray, tiers: List<List<String>>): ByteArray? {
        val entries = runCatching { topLevel(torrent) }.getOrNull() ?: return null
        val own = ArrayList<List<String>>()
        entries["announce-list"]?.let { raw -> runCatching { own.addAll(stringLists(raw)) } }
        if (own.isEmpty()) entries["announce"]?.let { raw -> runCatching { own.add(listOf(string(raw, 0).first)) } }
        val seen = own.flatten().toMutableSet()
        val merged = own + tiers.map { tier -> tier.filter { seen.add(it) } }.filter { it.isNotEmpty() }
        if (merged.size == own.size) return torrent
        entries["announce-list"] = encodeLists(merged)
        if ("announce" !in entries) entries["announce"] = encodeString(merged.first().first())
        val out = ByteArrayOutputStream(torrent.size + 256)
        out.write('d'.code)
        // Bencoded dictionaries are sorted by the keys' raw bytes.
        for (key in entries.keys.sortedWith { a, b -> compareBytes(a.toByteArray(), b.toByteArray()) }) {
            out.write(encodeString(key))
            out.write(entries.getValue(key))
        }
        out.write('e'.code)
        return out.toByteArray()
    }

    // ---- minimal bencode: spans of the top level, strings, lists of string lists ----

    private fun topLevel(b: ByteArray): LinkedHashMap<String, ByteArray> {
        require(b.isNotEmpty() && b[0] == 'd'.code.toByte())
        val out = LinkedHashMap<String, ByteArray>()
        var i = 1
        while (b[i] != 'e'.code.toByte()) {
            val (key, afterKey) = string(b, i)
            val end = skip(b, afterKey)
            out[key] = b.copyOfRange(afterKey, end)
            i = end
        }
        return out
    }

    /** The end (exclusive) of the element starting at [i]. */
    private fun skip(b: ByteArray, i: Int): Int = when (b[i].toInt().toChar()) {
        'i' -> b.indexOf('e'.code.toByte(), i) + 1
        'l', 'd' -> {
            var j = i + 1
            while (b[j] != 'e'.code.toByte()) j = skip(b, j)
            j + 1
        }
        else -> string(b, i).second
    }

    private fun string(b: ByteArray, i: Int): Pair<String, Int> {
        val colon = b.indexOf(':'.code.toByte(), i)
        val len = String(b, i, colon - i, Charsets.US_ASCII).toInt()
        return String(b, colon + 1, len, Charsets.UTF_8) to colon + 1 + len
    }

    private fun stringLists(raw: ByteArray): List<List<String>> {
        require(raw[0] == 'l'.code.toByte())
        val out = ArrayList<List<String>>()
        var i = 1
        while (raw[i] != 'e'.code.toByte()) {
            require(raw[i] == 'l'.code.toByte())
            val tier = ArrayList<String>()
            var j = i + 1
            while (raw[j] != 'e'.code.toByte()) {
                val (s, next) = string(raw, j)
                tier += s
                j = next
            }
            out += tier
            i = j + 1
        }
        return out
    }

    private fun ByteArray.indexOf(v: Byte, from: Int): Int {
        for (k in from until size) if (this[k] == v) return k
        throw IllegalArgumentException("not bencode")
    }

    private fun encodeString(s: String): ByteArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        return "${bytes.size}:".toByteArray(Charsets.US_ASCII) + bytes
    }

    private fun encodeLists(tiers: List<List<String>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write('l'.code)
        for (tier in tiers) {
            out.write('l'.code)
            tier.forEach { out.write(encodeString(it)) }
            out.write('e'.code)
        }
        out.write('e'.code)
        return out.toByteArray()
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (k in 0 until minOf(a.size, b.size)) {
            val d = (a[k].toInt() and 0xff) - (b[k].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}

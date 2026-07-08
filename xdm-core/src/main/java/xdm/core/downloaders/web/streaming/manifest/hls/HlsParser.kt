package xdm.core.downloaders.web.streaming.manifest.hls


import xdm.core.downloaders.web.streaming.manifest.common.resolveUri
import xdm.core.util.Logger
import xdm.core.util.StringUtils
import xdm.core.util.appendQuery
import xdm.core.util.getQuery
import java.net.URI

const val EXT_X_STREAM_INF = "#EXT-X-STREAM-INF:"
const val AUDIO = "AUDIO"
const val VIDEO = "VIDEO"
const val EXT_X_MEDIA = "#EXT-X-MEDIA:"
const val EXT_X_BYTERANGE = "#EXT-X-BYTERANGE:"
const val EXTINF = "#EXTINF:"
const val EXT_X_MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE:"
const val EXT_X_KEY = "#EXT-X-KEY:"
const val EXT_X_MAP = "#EXT-X-MAP:"
const val EXT_X_VERSION = "#EXT-X-VERSION:"
// Valueless tags: they appear on their own line with NO trailing colon.
const val EXT_X_I_FRAMES_ONLY = "#EXT-X-I-FRAMES-ONLY"
const val EXT_X_INDEPENDENT_SEGMENTS = "#EXT-X-INDEPENDENT-SEGMENTS"
// Also valueless. Matched with '==' (not startsWith) so it can't swallow #EXT-X-DISCONTINUITY-SEQUENCE.
const val EXT_X_DISCONTINUITY = "#EXT-X-DISCONTINUITY"
const val METHOD = "METHOD"

object HlsParser {

    private fun findSig(lines: Iterator<String>): Boolean {
        for (line in lines) {
            // Strip a leading UTF-8 BOM; some servers emit it before #EXTM3U.
            val trimmed = line.trim().removePrefix("﻿")
            if (trimmed.startsWith("#EXTM3U")) {
                return true
            }
        }
        return false
    }

    fun parseMediaSegments(
        manifestLines: Iterator<String>, playlistUrl: String
    ): Result<HlsPlaylist> {
        Logger.info("XDM", "Parsing manifest")
        return runCatching {
            try {
                val query = getQuery(playlistUrl) ?: ""
                val mediaSegments = ArrayList<HlsMediaSegment>()
                var mediaSequence: Long = 0
                var segmentEndOffset: Long = 0
                // Byte range for the NEXT media segment only; reset after each segment so a
                // segment without its own EXT-X-BYTERANGE doesn't inherit a stale range.
                var pendingByteRange: Pair<Long, Long>? = null
                var duration = 0.0
                var totalDuration = 0.0
                var anyByteRange = false
                var isEncrypted = false
                // True if ANY segment was encrypted (isEncrypted only tracks the current key state,
                // which can flip to NONE mid-playlist).
                var anyEncrypted = false
                var hasInitMap = false
                val baseUrl = URI.create(playlistUrl)
                var keyFrameOnly = false
                var missingIv = true
                var version = -1

                var keyUrl: URI? = null
                var iv: String? = null
                var isIndependent = false
                // EXT-X-DISCONTINUITY applies to the segment that follows it.
                var pendingDiscontinuity = false
                var anyDiscontinuity = false

                if (!findSig(manifestLines)) {
                    val msg = "Invalid HLS manifest, header signature not found !"
                    Logger.error(msg)
                    throw Exception(msg)
                }

                for (lineText in manifestLines) {
                    val line = lineText.trim()
                    if (line.isEmpty()) continue
                    Logger.info("XDM", line)
                    if (line[0] != '#') {
                        if (isEncrypted && missingIv) {
                            iv = toBigEndian128BitHex(mediaSequence)
                        }
                        if (isEncrypted) anyEncrypted = true
                        if (pendingDiscontinuity) anyDiscontinuity = true
                        val segment = HlsMediaSegment(
                            url = applyQuery(baseUrl, resolveUri(baseUrl, line), query),
                            byteRange = pendingByteRange,
                            duration,
                            keyUrl?.let { applyQuery(baseUrl, it, query) },
                            iv,
                            isEncrypted,
                            discontinuity = pendingDiscontinuity,
                        )
                        mediaSegments.add(segment)
                        pendingByteRange = null
                        pendingDiscontinuity = false
                        mediaSequence++
                        totalDuration += duration
                    } else if (line == EXT_X_DISCONTINUITY) {
                        pendingDiscontinuity = true
                    } else if (line.startsWith(EXT_X_I_FRAMES_ONLY)) {
                        keyFrameOnly = true
                    } else if (line.startsWith(EXT_X_VERSION)) {
                        version = line.substring(EXT_X_VERSION.length).trim().toInt()
                    } else if (line.startsWith(EXT_X_BYTERANGE)) {
                        anyByteRange = true
                        val attrList = line.substring(EXT_X_BYTERANGE.length).trim()
                        parseByteRange(attrList)?.let {
                            val (offset, length) = it
                            val start = if (offset > 0) offset else segmentEndOffset
                            segmentEndOffset = start + length
                            pendingByteRange = Pair(start, length)
                        }
                    } else if (line.startsWith(EXTINF)) {
                        val attrs = line.substring(EXTINF.length).trim()
                        if (attrs.isNotEmpty()) {
                            duration = attrs.split(",")[0].toDouble()
                        }
                    } else if (line.startsWith(EXT_X_MEDIA_SEQUENCE)) {
                        mediaSequence =
                            line.substring(EXT_X_MEDIA_SEQUENCE.length).trim().toLong()
                    } else if (line.startsWith(EXT_X_KEY)) {
                        isEncrypted = true
                        val attributes = parseAttributes(line.substring(EXT_X_KEY.length))
                        val method = attributes[METHOD]
                        if (method != null) {
                            val keyFormat = attributes.getOrDefault("KEYFORMAT", "identity")
                            when {
                                StringUtils.equalsIgnoreCase("NONE", method) -> {
                                    // Clear the key state so subsequent clear segments don't carry a
                                    // stale keyUrl/iv from the previous EXT-X-KEY.
                                    isEncrypted = false
                                    keyUrl = null
                                    iv = null
                                    missingIv = true
                                    continue
                                }

                                StringUtils.equalsIgnoreCase("AES-128", method)
                                    && StringUtils.equalsIgnoreCase("identity", keyFormat) -> {
                                    // Full-segment AES-128 with a clear (identity) key — the only
                                    // encryption we support.
                                    keyUrl = resolveUri(baseUrl, attributes["URI"]!!)
                                    if (!attributes.containsKey("IV")) {
                                        iv = null
                                        missingIv = true
                                    } else {
                                        iv = attributes["IV"]
                                        missingIv = false
                                    }
                                }

                                StringUtils.equalsIgnoreCase("SAMPLE-AES", method)
                                    || StringUtils.equalsIgnoreCase("SAMPLE-AES-CTR", method)
                                    || StringUtils.equalsIgnoreCase("SAMPLE-AES-CENC", method) -> {
                                    // SAMPLE-AES encrypts only the media samples inside the container
                                    // (codec-aware) rather than the whole segment. We only support
                                    // full-segment AES-128, so reject it explicitly.
                                    Logger.error("XDM", "SAMPLE-AES encryption is not supported")
                                    throw Exception("SAMPLE-AES encryption is not supported (only full-segment AES-128)")
                                }

                                else -> {
                                    Logger.error("XDM", "Unsupported key format or encryption method: $method / $keyFormat")
                                    throw Exception("Unsupported encryption")
                                }
                            }
                        }
                    } else if (line.startsWith(EXT_X_MAP)) {
                        val attributes =
                            parseAttributes(line.substring(EXT_X_MAP.length))
                        attributes["URI"]?.let { uri ->
                            hasInitMap = true
                            val segment =
                                HlsMediaSegment(
                                    url = applyQuery(baseUrl, resolveUri(baseUrl, uri), query),
                                    byteRange = parseByteRange(
                                        attributes["BYTERANGE"]
                                    ),
                                    duration = 0.0,
                                    keyUrl?.let { applyQuery(baseUrl, it, query) },
                                    iv,
                                    isEncrypted
                                )
                            mediaSegments.add(segment)
                        }
                    } else if (line.startsWith(EXT_X_INDEPENDENT_SEGMENTS)) {
                        isIndependent = true
                    }
                }

                if (mediaSegments.isEmpty()) {
                    val msg = "No media segment in playlist"
                    Logger.error(msg)
                    throw Exception("No media segment in playlist")
                }
                HlsPlaylist(
                    mediaSegments,
                    encrypted = anyEncrypted,
                    anyByteRange,
                    totalDuration,
                    keyFrameOnly,
                    hasInitSection = hasInitMap,
                    version,
                    isIndependent,
                    hasDiscontinuity = anyDiscontinuity,
                )
            } catch (e: Exception) {
                Logger.error("Error parsing manifest", e)
                throw e
            }
        }
    }

    fun parseMasterPlaylist(
        manifestLines: Iterator<String>, playlistUrl: String
    ): Result<List<HlsMasterPlaylist>> {
        return runCatching {
            // Bind each EXT-X-STREAM-INF to the URI line that immediately follows it, rather than
            // pairing two parallel lists by index. That keeps a stray/extra URI (e.g. from an
            // EXT-X-I-FRAME-STREAM-INF, whose URI is an attribute, not a line) from shifting the
            // pairing and throwing IndexOutOfBounds.
            val variants = ArrayList<Pair<Map<String, String>, URI>>()
            val mapExtMedia = ArrayList<Map<String, String>>()
            val containers = ArrayList<HlsMasterPlaylist>()
            val baseUrl = URI.create(playlistUrl)
            var pendingStreamInf: Map<String, String>? = null
            var isIndependent = false

            if (!findSig(manifestLines)) {
                throw Exception("Invalid HLS manifest, header signature not found !")
            }

            for (lineText in manifestLines) {
                val line = lineText.trim()
                if (line.isEmpty()) continue

                if (line[0] != '#') {
                    val streamInf = pendingStreamInf
                    if (streamInf != null) {
                        variants.add(streamInf to resolveUri(baseUrl, line))
                        pendingStreamInf = null
                    }
                    // else: a URI with no preceding EXT-X-STREAM-INF (unexpected) — skip it.
                } else if (line.startsWith(EXT_X_STREAM_INF)) {
                    pendingStreamInf = parseAttributes(line.substring(EXT_X_STREAM_INF.length))
                } else if (line.startsWith(EXT_X_MEDIA)) {
                    mapExtMedia.add(parseAttributes(line.substring(EXT_X_MEDIA.length)))
                } else if (line.startsWith(EXT_X_INDEPENDENT_SEGMENTS)) {
                    isIndependent = true
                }
            }

            if (variants.isEmpty()) {
                throw Exception("No attribute in stream info, can't parse HLS manifest")
            }

            for ((extStreamInf, url) in variants) {
                var alternateAudioFound = false
                extStreamInf[AUDIO]?.let { groupId ->
                    alternateAudioFound = true
                    containers.addAll(mapExtMedia.filter { media -> groupId == media["GROUP-ID"] && AUDIO == media["TYPE"] }
                        .map { media ->
                            HlsMasterPlaylist(
                                videoPlaylist = url,
                                audioPlaylist = media["URI"]?.let { resolveUri(baseUrl, it) },
                                attributes = extStreamInf + media,
                                isIndependent,
                            )
                        })
                }
                if (alternateAudioFound) {
                    continue
                }

                var alternateVideoFound = false
                extStreamInf[VIDEO]?.let { groupId ->
                    alternateVideoFound = true
                    containers.addAll(mapExtMedia.filter { media -> groupId == media["GROUP-ID"] && VIDEO == media["TYPE"] }
                        .map { media ->
                            HlsMasterPlaylist(
                                videoPlaylist = media["URI"]?.let { resolveUri(baseUrl, it) },
                                audioPlaylist = url,
                                attributes = extStreamInf + media,
                                isIndependent,
                            )
                        })
                }
                if (alternateVideoFound) {
                    continue
                }
                Logger.info("Playlist without alternative rendering found!")
                containers.add(
                    HlsMasterPlaylist(
                        videoPlaylist = url, attributes = extStreamInf,
                        independent = isIndependent,
                    )
                )
            }
            containers
        }
    }

    fun isMasterPlaylist(manifestLines: Iterator<String>): Boolean {
        return manifestLines.asSequence().any { s -> StringUtils.containsIgnoreCase(s, EXT_X_STREAM_INF) }
    }

    private fun parseByteRange(str: String?): Pair<Long, Long>? {
        if (str == null) return null
        val attrs = str.split("@")
        return Pair(if (attrs.size == 2) attrs[1].toLong() else 0, attrs[0].toLong())
    }

    private fun effectivePort(u: URI): Int =
        if (u.port != -1) u.port else when (u.scheme?.lowercase()) {
            "https" -> 443
            "http" -> 80
            else -> -1
        }

    private fun sameOrigin(a: URI, b: URI): Boolean =
        a.scheme.equals(b.scheme, ignoreCase = true) &&
            a.host.equals(b.host, ignoreCase = true) &&
            effectivePort(a) == effectivePort(b)

    /**
     * Carry the manifest's query string onto a resolved child URL, but only when the child stays on
     * the same origin as the playlist. Otherwise a host-specific auth token would leak to a
     * different CDN host (and typically 403). [appendQuery] itself no-ops when the URL already
     * carries its own query string.
     */
    private fun applyQuery(baseUrl: URI, resolved: URI, query: String): String {
        val s = resolved.toString()
        return if (query.isNotEmpty() && sameOrigin(baseUrl, resolved)) appendQuery(s, query) else s
    }
}

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
const val EXT_X_I_FRAMES_ONLY = "#EXT-X-I-FRAMES-ONLY:"
const val EXT_X_INDEPENDENT_SEGMENTS = "#EXT-X-INDEPENDENT-SEGMENTS:"
const val METHOD = "METHOD"

object HlsParser {

    private fun findSig(lines: Iterator<String>): Boolean {
        for (line in lines) {
            if (line.trim().startsWith("#EXTM3U")) {
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
                var startOffset: Long = 0
                var segmentLength: Long = 0
                var segmentEndOffset: Long = 0
                var duration = 0.0
                var totalDuration = 0.0
                var hasByteRange = false
                var isEncrypted = false
                var hasInitMap = false
                val baseUrl = URI.create(playlistUrl)
                var keyFrameOnly = false
                var missingIv = true
                var version = -1

                var keyUrl: URI? = null
                var iv: String? = null
                var isIndependent = false

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
                        val segment = HlsMediaSegment(
                            url = appendQuery(resolveUri(baseUrl, line).toString(), query),
                            byteRange = if (hasByteRange) Pair(startOffset, segmentLength) else null,
                            duration,
                            keyUrl?.let { appendQuery(it.toString(), query) },
                            iv,
                            isEncrypted
                        )
                        mediaSegments.add(segment)
                        mediaSequence++
                        totalDuration += duration
                    } else if (line.startsWith(EXT_X_I_FRAMES_ONLY)) {
                        keyFrameOnly = true
                    } else if (line.startsWith(EXT_X_VERSION)) {
                        version = line.substring(EXT_X_VERSION.length).trim().toInt()
                    } else if (line.startsWith(EXT_X_BYTERANGE)) {
                        hasByteRange = true
                        val attrList = line.substring(EXT_X_BYTERANGE.length).trim()
                        parseByteRange(attrList)?.let {
                            val (offset, length) = it
                            startOffset = if (offset > 0) {
                                offset
                            } else {
                                segmentEndOffset
                            }
                            segmentLength = length
                            segmentEndOffset += segmentLength
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
                        if (attributes.containsKey(METHOD)) {
                            if (StringUtils.equalsIgnoreCase("NONE", attributes[METHOD])) {
                                isEncrypted = false
                                continue
                            }
                            if (StringUtils.equalsIgnoreCase("AES-128", attributes[METHOD])
                                && StringUtils.equalsIgnoreCase(
                                    "identity",
                                    attributes.getOrDefault("KEYFORMAT", "identity")
                                )
                            ) {
                                keyUrl = resolveUri(baseUrl, attributes["URI"]!!)
                                if (!attributes.containsKey("IV")) {
                                    iv = null
                                    missingIv = true
                                } else {
                                    iv = attributes["IV"]
                                    missingIv = false
                                }
                            } else {
                                Logger.error("XDM", "Unsupported key format or encryption method")
                                throw Exception("Unsupported encryption")
                            }
                        }
                    } else if (line.startsWith(EXT_X_MAP)) {
                        val attributes =
                            parseAttributes(line.substring(EXT_X_MAP.length))
                        attributes["URI"]?.let { uri ->
                            hasInitMap = true
                            val segment =
                                HlsMediaSegment(
                                    url = appendQuery(resolveUri(baseUrl, uri).toString(), query),
                                    byteRange = parseByteRange(
                                        attributes["BYTERANGE"]
                                    ),
                                    duration = 0.0,
                                    keyUrl?.let { appendQuery(it.toString(), query) },
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
                    encrypted = isEncrypted,
                    hasByteRange,
                    totalDuration,
                    keyFrameOnly,
                    hasInitSection = hasInitMap,
                    version,
                    isIndependent,
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
            val mapExtStreamInf = ArrayList<Map<String, String>>()
            val mapExtMedia = ArrayList<Map<String, String>>()
            val containers = ArrayList<HlsMasterPlaylist>()
            val baseUrl = URI.create(playlistUrl)
            val urls = ArrayList<URI>()
            var isIndependent = false

            if (!findSig(manifestLines)) {
                throw Exception("Invalid HLS manifest, header signature not found !")
            }

            for (lineText in manifestLines) {
                val line = lineText.trim()
                if (line.isEmpty()) continue

                if (line[0] != '#') {
                    urls.add(resolveUri(baseUrl, line))
                } else if (line.startsWith(EXT_X_STREAM_INF)) {
                    mapExtStreamInf.add(parseAttributes(line.substring(EXT_X_STREAM_INF.length)))
                } else if (line.startsWith(EXT_X_MEDIA)) {
                    mapExtMedia.add(parseAttributes(line.substring(EXT_X_MEDIA.length)))
                } else if (line.startsWith(EXT_X_INDEPENDENT_SEGMENTS)) {
                    isIndependent = true
                }
            }

            if (mapExtStreamInf.isEmpty()) {
                throw Exception("No attribute in stream info, can't parse HLS manifest")
            }

            for ((index, url) in urls.withIndex()) {
                val extStreamInf = mapExtStreamInf[index]
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
}

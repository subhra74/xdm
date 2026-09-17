package xdm.core.downloaders.web.streaming.downloader.hls

import xdm.core.CoreConfig
import xdm.core.downloaders.*
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.downloaders.web.loadHlsState
import xdm.core.downloaders.web.saveState
import xdm.core.downloaders.web.streaming.downloader.HlsTaskContext
import xdm.core.downloaders.web.streaming.downloader.StreamingChunk
import xdm.core.downloaders.web.streaming.downloader.StreamingDownloaderTask
import xdm.core.downloaders.web.streaming.manifest.hls.HlsParser
import xdm.core.downloaders.web.streaming.manifest.hls.HlsPlaylist
import xdm.core.media.muxer.Muxer
import xdm.core.network.http.PoolingHttpClient
import xdm.core.util.CoreUtils
import xdm.core.util.Logger
import xdm.core.util.ManifestUtils.downloadManifestBytes
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

fun makeContext(
    taskInfo: HlsDownloadTaskInfo, http: PoolingHttpClient, host: DownloadHost, configDir: String,
): HlsTaskContext {
    loadHlsState(id = taskInfo.id, configDir = configDir, http = http, host = host).onSuccess {
        return it
    }
    Logger.info("Unable to load saved download state, starting new download: ${taskInfo.id}")
    return HlsTaskContext(
        id = taskInfo.id,
        chunks = ArrayList(),
        httpClient = http,
        downloadHost = host,
        tempFolder = taskInfo.tempDir,
        tempFileName = "${taskInfo.id}.mp4",
        url = taskInfo.url,
        headers = taskInfo.headers,
        cookie = taskInfo.cookie,
        audioUrl = taskInfo.audioUrl,
        audioOnly = taskInfo.audioOnly,
        independent = taskInfo.independent,
        encrypted = false
    )
}

//fun loadContext(
//    id: Long,
//    configDir: String,
//    http: PoolingHttpClient,
//    host: DownloadHost,
//) = loadHlsState(id = id, configDir = configDir, http = http, host = host).getOrThrow()

class HlsDownloaderTask : StreamingDownloaderTask {

//    constructor(
//        id: Long,
//        configDir: String,
//        http: PoolingHttpClient,
//        host: DownloadHost,
//        muxer: Muxer,
//        config: CoreConfig
//    ) : super(loadContext(id, configDir, http, host), configDir, muxer, config)

    constructor(
        taskInfo: HlsDownloadTaskInfo,
        http: PoolingHttpClient,
        muxer: Muxer,
        host: DownloadHost,
        configDir: String,
        config: CoreConfig
    ) : super(makeContext(taskInfo, http, host, configDir), configDir, muxer, config)

    private val keyCache = ConcurrentHashMap<String, ByteArray>()
    private val decryptBuffer = ByteArray(256 * 1024)

    override fun initDownload(): DownloadStatusInfo.InitInfo? {
        val hlsContext = context as HlsTaskContext
        context.hasSeparateStreams = hlsContext.audioUrl != null
        //val independent = context.independent //In case master playlist contain #EXT-X-INDEPENDENT-SEGMENTS
        val latch = CountDownLatch(if (context.hasSeparateStreams) 2 else 1)
        val videoManifestContent = AtomicReference<Iterator<String>>()
        val audioManifestContent = AtomicReference<Iterator<String>>()
        val error = AtomicBoolean(false)
        loadManifest(hlsContext.url, videoManifestContent, latch, error)
        if (context.hasSeparateStreams) {
            loadManifest(hlsContext.audioUrl!!, audioManifestContent, latch, error)
        }
        try {
            latch.await()
            val (videoPlaylist, audioPlaylist) = parseManifest(videoManifestContent, audioManifestContent)
            if (!context.independent) {
                context.independent = videoPlaylist.independent
            }
            retrieveKeys(videoPlaylist, audioPlaylist)
            // Decryption must run if EITHER stream is encrypted. Setting this from the video
            // playlist alone left audio-only-encrypted streams with undecrypted .enc segments,
            // which were then handed to the muxer under the wrong (encrypted) temp file name.
            if (videoPlaylist.encrypted || audioPlaylist?.encrypted == true) {
                context.encrypted = true
            }
            // A discontinuity in either stream means the muxer must repair the timeline so the
            // output MP4 stays monotonic across the timestamp reset.
            if (videoPlaylist.hasDiscontinuity || audioPlaylist?.hasDiscontinuity == true) {
                context.discontinuous = true
            }
            context.chunks.ensureCapacity(videoPlaylist.mediaSegments.size + (audioPlaylist?.mediaSegments?.size ?: 0))
            context.chunks.addAll(videoPlaylist.mediaSegments.mapIndexed { index, ms ->
                StreamingChunk(
                    id = CoreUtils.uniqueId(),
                    sequence = index.toLong(),
                    status = AtomicReference(ChunkStatus.Ready),
                    url = ms.url,
                    keyUrl = ms.keyUrl,
                    iv = ms.iv,
                    byteRange = ms.byteRange,
                    tag = "VIDEO",
                    error = AtomicReference(null),
                    fileHandle = AtomicReference(null),
                    encrypted = ms.encrypted
                )
            })
            val count = videoPlaylist.mediaSegments.size
            audioPlaylist?.let {
                context.chunks.addAll(it.mediaSegments.mapIndexed { index, ms ->
                    StreamingChunk(
                        id = CoreUtils.uniqueId(),
                        sequence = count + index.toLong(),
                        status = AtomicReference(ChunkStatus.Ready),
                        url = ms.url,
                        keyUrl = ms.keyUrl?.toString(),
                        iv = ms.iv,
                        byteRange = ms.byteRange,
                        tag = "AUDIO",
                        error = AtomicReference(null),
                        fileHandle = AtomicReference(null),
                        encrypted = ms.encrypted
                    )
                })
            }
            return DownloadStatusInfo.InitInfo(
                id = context.id,
                url = context.url,
                isRedirect = false,
                fileSize = null,
                contentDisposition = null,
                contentType = null,
                videoExt = fileExt()
            )
        } catch (e: Exception) {
            Logger.error("XDM", "Error downloading manifests", e)
            error.set(true)
            return null
        }
    }

    override fun fileExt(): String = ".mp4"

    override fun downloadType(): DownloadType = DownloadType.Hls
    override fun saveContext() {
        synchronized(this) {
            saveState(context as HlsTaskContext, configDir)
        }
    }

    override fun isIndependentSegment() = (context as HlsTaskContext).independent

    override fun hasDiscontinuity() = (context as HlsTaskContext).discontinuous

    private fun parseManifest(
        videoManifestContent: AtomicReference<Iterator<String>>, audioManifestContent: AtomicReference<Iterator<String>>
    ): Pair<HlsPlaylist, HlsPlaylist?> {
        val videoPlaylist =
            HlsParser.parseMediaSegments(videoManifestContent.get(), (context as HlsTaskContext).url).getOrThrow()
        var audioPlaylist: HlsPlaylist? = null
        context.audioUrl?.let {
            audioPlaylist = HlsParser.parseMediaSegments(audioManifestContent.get(), it).getOrThrow()
        }
        return Pair(videoPlaylist, audioPlaylist)
    }

    private fun retrieveKeys(
        videoPlayList: HlsPlaylist, audioPlayList: HlsPlaylist?
    ) {
        val hlsContext = context as HlsTaskContext
        val error = AtomicBoolean(false)
        val keyUrls = HashSet<String>()
        if (videoPlayList.encrypted) {
            keyUrls.addAll(videoPlayList.mediaSegments.mapNotNull { it.keyUrl })
        }
        if (audioPlayList != null && audioPlayList.encrypted) {
            keyUrls.addAll(audioPlayList.mediaSegments.mapNotNull { it.keyUrl })
        }
        if (context.stopFlag.get()) {
            return
        }
        if (keyUrls.isEmpty()) return
        val counter = CountDownLatch(keyUrls.size)
        for (keyUrl in keyUrls) {
            Logger.info("Downloading key: $keyUrl")
            executorService.submit {
                try {
                    if (!error.get() && !context.stopFlag.get()) {
                        Logger.info("Headers: ${hlsContext.headers}")
                        val bytes = downloadManifestBytes(
                            context.httpClient, keyUrl, hlsContext.headers, hlsContext.cookie, context.stopFlag,
                            recordFetchError
                        )
                        if (bytes != null) {
                            keyCache[keyUrl] = bytes
                            if (bytes.size != 16) {
                                throw Exception("Invalid key size")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logger.error("XDM", "Error downloading keys", e)
                    error.set(true)
                } finally {
                    counter.countDown()
                }
            }
        }
        counter.await()
        if (error.get()) {
            throw IOException("Unable to get keys")
        }
    }

    override fun postProcessChunks() {
        val context = context as HlsTaskContext
        if (context.encrypted) {
            for (chunk in context.chunks) {
                if (context.stopFlag.get()) break
                decryptChunk(chunk)
            }
        }
    }

    private fun decryptChunk(chunk: StreamingChunk) {
        if (!chunk.encrypted) {
            return
        }
        val encChunkFile = getChunkTempFileName(chunk)
        // Drop only the trailing ".enc" suffix so the decrypted path exactly matches what
        // getChunkTempFileName(encrypted=false) yields for muxing (a parent dir could contain ".enc").
        val decChunkFile = encChunkFile.removeSuffix(".enc")
        Logger.info("XDM", "Decrypting chunk: $encChunkFile -> $decChunkFile")
        val key = keyCache[chunk.keyUrl] ?: throw Exception("Key missing")
        val iv = chunk.iv
        Logger.info("XDM", "Key: $key id: $iv")
        FileOutputStream(decChunkFile).use { output ->
            FileInputStream(encChunkFile).use { input ->
                getCypherStream(input, key, strToIvBytes(chunk.iv!!)).use { cypherIn ->
                    while (!context.stopFlag.get()) {
                        val x = cypherIn.read(decryptBuffer)
                        if (x == -1) break
                        output.write(decryptBuffer, 0, x)
                    }
                }
            }
        }
        if (!context.stopFlag.get()) {
            chunk.encrypted = false
        }
    }


    private fun getCypherStream(input: InputStream, key: ByteArray, iv: ByteArray): InputStream {
        val cipher: Cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val cipherKey = SecretKeySpec(key, "AES")
        val cipherIV = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, cipherKey, cipherIV)
        return CipherInputStream(input, cipher)
    }

    private fun strToIvBytes(str: String): ByteArray {
        var str = str
        if (str.lowercase(Locale.getDefault()).startsWith("0x")) {
            str = str.substring(2)
        }
        val ivData: ByteArray = BigInteger(str, 16).toByteArray()
        val ivDataWithPadding = ByteArray(16)
        val offset = if (ivData.size > 16) ivData.size - 16 else 0
        System.arraycopy(
            ivData, offset, ivDataWithPadding, ivDataWithPadding.size - ivData.size + offset,
            ivData.size - offset
        )
        return ivDataWithPadding
    }

}
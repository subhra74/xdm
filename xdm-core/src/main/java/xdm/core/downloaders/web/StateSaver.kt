package xdm.core.downloaders.web

import xdm.core.downloaders.DownloadHost
import xdm.core.downloaders.web.http.Chunk
import xdm.core.downloaders.web.http.ChunkStatus
import xdm.core.downloaders.web.http.HttpTaskContext
import xdm.core.downloaders.web.streaming.downloader.DashTaskContext
import xdm.core.downloaders.web.streaming.downloader.HlsTaskContext
import xdm.core.downloaders.web.streaming.downloader.StreamingChunk
import xdm.core.network.http.HeaderMap
import xdm.core.network.http.PoolingHttpClient
import xdm.core.util.AtomicIO
import xdm.core.util.Logger
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private fun writeChunks(chunkMap: Map<Long, Chunk>, w: DataOutputStream) {
    w.writeInt(chunkMap.size)
    for ((k, v) in chunkMap) {
        w.writeLong(k)
        writeChunk(v, w)
    }
}

private fun writeChunk(chunk: Chunk, w: DataOutputStream) {
    chunk.apply {
        w.writeLong(id)
        w.writeLong(offset)
        w.writeLong(length.get())
        w.writeLong(downloaded.get())
        w.writeUTF(status.toString())
    }
}

private fun writeChunks(chunkMap: List<StreamingChunk>, w: DataOutputStream) {
    w.writeInt(chunkMap.size)
    for (c in chunkMap) {
        writeHlsChunk(c, w)
    }
}

private fun writeHlsChunk(chunk: StreamingChunk, w: DataOutputStream) {
    chunk.apply {
        w.writeLong(id)
        w.writeLong(sequence)
        w.writeLong(downloaded.get())
        w.writeLong(length.get())
        w.writeUTF(status.toString())
        w.writeBoolean(contentType != null)
        contentType?.let { w.writeUTF(it) }
        w.writeUTF(url)
        w.writeBoolean(chunk.byteRange != null)
        byteRange?.let {
            w.writeLong(it.first)
            w.writeLong(it.second)
        }
        w.writeBoolean(keyUrl != null)
        keyUrl?.let { w.writeUTF(it) }
        w.writeBoolean(iv != null)
        iv?.let { w.writeUTF(it) }
        w.writeUTF(tag)
        w.writeBoolean(encrypted)
    }
}

private fun readHlsChunk(r: DataInputStream): StreamingChunk {
    return StreamingChunk(
        id = r.readLong(),
        sequence = r.readLong(),
        downloaded = AtomicLong(r.readLong()),
        length = AtomicLong(r.readLong()),
        status = AtomicReference(
            ChunkStatus.valueOf(r.readUTF())
        ),
        contentType = if (r.readBoolean()) r.readUTF() else null,
        url = r.readUTF(),
        byteRange = if (r.readBoolean()) {
            Pair(r.readLong(), r.readLong())
        } else null,
        keyUrl = if (r.readBoolean()) r.readUTF() else null,
        iv = if (r.readBoolean()) r.readUTF() else null,
        tag = r.readUTF(),
        fileHandle = AtomicReference(null),
        error = AtomicReference(null),
        encrypted = r.readBoolean(),
    )
}

private fun readStreamingChunks(r: DataInputStream): ArrayList<StreamingChunk> {
    val count = r.readInt()
    val chunkMap = ArrayList<StreamingChunk>()
    (0..<count).forEach { _ ->
        val chunk = readHlsChunk(r)
        chunkMap.add(chunk)
    }
    return chunkMap
}

private fun readChunk(r: DataInputStream): Chunk {
    return Chunk(
        id = r.readLong(),
        offset = r.readLong(),
        length = AtomicLong(r.readLong()),
        downloaded = AtomicLong(r.readLong()),
        status = AtomicReference(
            ChunkStatus.valueOf(r.readUTF())
        ),
        fileHandle = AtomicReference(null),
        lastTakeOver = AtomicLong(0)
    )
}

private fun readChunks(r: DataInputStream): MutableMap<Long, Chunk> {
    val count = r.readInt()
    val chunkMap = HashMap<Long, Chunk>()
    for (i in 0..<count) {
        val id = r.readLong()
        val chunk = readChunk(r)
        chunkMap[id] = chunk
    }
    return chunkMap
}

private fun writeHeaders(headerMap: HeaderMap, w: DataOutputStream) {
    w.writeInt(headerMap.size)
    for ((k, v) in headerMap) {
        w.writeUTF(k)
        w.writeInt(v.size)
        for (value in v) {
            w.writeUTF(value)
        }
    }
}

private fun readHeaders(r: DataInputStream): HeaderMap {
    val headerMap = HashMap<String, List<String>>()
    if (!r.readBoolean()) return headerMap
    val count = r.readInt()
    for (i in 0..<count) {
        val k = r.readUTF()
        val n = r.readInt()
        val list = ArrayList<String>()
        for (j in 0..<n) {
            list.add(r.readUTF())
        }
        headerMap[k] = list
    }
    return headerMap
}

private fun writeContext(context: HttpTaskContext, out: DataOutputStream) {
    context.apply {
        out.writeLong(id)
        out.writeUTF(tempFolder)
        out.writeUTF(tempFileName)
        writeChunks(chunks, out)
        out.writeBoolean(init.get())
        out.writeBoolean(totalSize != null)
        totalSize?.let { out.writeLong(it) }
        out.writeLong(downloaded.get())
        out.writeUTF(url)
        out.writeBoolean(contentType != null)
        contentType?.let { out.writeUTF(it) }
        out.writeBoolean(headers != null)
        headers?.let { writeHeaders(it, out) }
        out.writeBoolean(cookie != null)
        cookie?.let { out.writeUTF(it) }
        out.writeBoolean(completed.get())
        out.writeBoolean(tempFileCreated.get())
        out.writeBoolean(diskError.get())
    }
}

private fun writeHlsContext(context: HlsTaskContext, out: DataOutputStream) {
    context.apply {
        out.writeLong(id)
        out.writeUTF(tempFolder)
        out.writeUTF(tempFileName)
        writeChunks(chunks, out)
        out.writeBoolean(init.get())
        out.writeBoolean(totalSize != null)
        totalSize?.let { out.writeLong(it) }
        out.writeLong(downloaded.get())
        out.writeBoolean(contentType != null)
        contentType?.let { out.writeUTF(it) }
        out.writeBoolean(headers != null)
        headers?.let { writeHeaders(it, out) }
        out.writeBoolean(cookie != null)
        cookie?.let { out.writeUTF(it) }
        out.writeBoolean(completed.get())
        out.writeBoolean(hasSeparateStreams)
        out.writeInt(pieceCompletedCount.get())
        out.writeUTF(url)
        out.writeBoolean(audioUrl != null)
        audioUrl?.let { out.writeUTF(it) }
        out.writeBoolean(audioOnly)
        out.writeBoolean(independent)
        out.writeBoolean(encrypted)
    }
}

private fun writeDashContext(context: DashTaskContext, out: DataOutputStream) {
    context.apply {
        out.writeLong(id)
        out.writeUTF(tempFolder)
        out.writeUTF(tempFileName)
        writeChunks(chunks, out)
        out.writeBoolean(init.get())
        out.writeBoolean(totalSize != null)
        totalSize?.let { out.writeLong(it) }
        out.writeLong(downloaded.get())
        out.writeBoolean(contentType != null)
        contentType?.let { out.writeUTF(it) }
        out.writeBoolean(headers != null)
        headers?.let { writeHeaders(it, out) }
        out.writeBoolean(cookie != null)
        cookie?.let { out.writeUTF(it) }
        out.writeBoolean(completed.get())
        out.writeBoolean(hasSeparateStreams)
        out.writeInt(pieceCompletedCount.get())
        out.writeUTF(url)
        out.writeUTF(audioMime)
        out.writeUTF(videoMime)
    }
}

fun readContext(r: DataInputStream, host: DownloadHost): HttpTaskContext {
    return HttpTaskContext(
        id = r.readLong(),
        tempFolder = r.readUTF(),
        tempFileName = r.readUTF(),
        chunks = readChunks(r),
        init = AtomicBoolean(r.readBoolean()),
        totalSize = if (r.readBoolean()) r.readLong() else null,
        downloaded = AtomicLong(r.readLong()),
        url = r.readUTF(),
        contentType = if (r.readBoolean()) r.readUTF() else null,
        headers = readHeaders(r),
        cookie = if (r.readBoolean()) r.readUTF() else null,
        stopFlag = AtomicBoolean(false),
        completed = AtomicBoolean(r.readBoolean()),
        tempFileCreated = AtomicBoolean(r.readBoolean()),
        diskError = AtomicBoolean(r.readBoolean()),
        downloadHost = host,
    ).apply { Logger.info(this) }
}

private fun readHlsContext(r: DataInputStream, http: PoolingHttpClient, host: DownloadHost): HlsTaskContext {
    return HlsTaskContext(
        id = r.readLong(),
        tempFolder = r.readUTF(),
        tempFileName = r.readUTF(),
        chunks = readStreamingChunks(r),
        init = AtomicBoolean(r.readBoolean()),
        totalSize = if (r.readBoolean()) r.readLong() else null,
        downloaded = AtomicLong(r.readLong()),
        contentType = if (r.readBoolean()) r.readUTF() else null,
        headers = readHeaders(r),
        cookie = if (r.readBoolean()) r.readUTF() else null,
        httpClient = http,
        stopFlag = AtomicBoolean(false),
        completed = AtomicBoolean(r.readBoolean()),
        hasSeparateStreams = r.readBoolean(),
        pieceCompletedCount = AtomicInteger(r.readInt()),
        url = r.readUTF(),
        audioUrl = if (r.readBoolean()) r.readUTF() else null,
        audioOnly = r.readBoolean(),
        downloadHost = host,
        independent = r.readBoolean(),
        encrypted = r.readBoolean(),
    ).apply { Logger.info(this) }
}

private fun readDashContext(r: DataInputStream, http: PoolingHttpClient, host: DownloadHost): DashTaskContext {
    return DashTaskContext(
        id = r.readLong(),
        tempFolder = r.readUTF(),
        tempFileName = r.readUTF(),
        chunks = readStreamingChunks(r),
        init = AtomicBoolean(r.readBoolean()),
        totalSize = if (r.readBoolean()) r.readLong() else null,
        downloaded = AtomicLong(r.readLong()),
        contentType = if (r.readBoolean()) r.readUTF() else null,
        headers = readHeaders(r),
        cookie = if (r.readBoolean()) r.readUTF() else null,
        httpClient = http,
        stopFlag = AtomicBoolean(false),
        completed = AtomicBoolean(r.readBoolean()),
        hasSeparateStreams = r.readBoolean(),
        pieceCompletedCount = AtomicInteger(r.readInt()),
        url = r.readUTF(),
        audioMime = r.readUTF(),
        videoMime = r.readUTF(),
        downloadHost = host,
    ).apply { Logger.info(this) }
}

@Synchronized
fun saveState(context: HttpTaskContext, configDir: String) {
    //Logger.info(context)
    AtomicIO.writeTransacted("${context.id}.state", configDir) { fs ->
        writeContext(context, fs)
    }.onFailure { Logger.error("XDM", "Error saving state", it) }
}

@Synchronized
fun saveState(context: HlsTaskContext, configDir: String) {
    //Logger.info(context)
    AtomicIO.writeTransacted("${context.id}.state", configDir) { fs ->
        writeHlsContext(context, fs)
    }.onFailure { Logger.error("XDM", "Error saving state", it) }
}

@Synchronized
fun saveState(context: DashTaskContext, configDir: String) {
    //Logger.info(context)
    AtomicIO.writeTransacted("${context.id}.state", configDir) { fs ->
        writeDashContext(context, fs)
    }.onFailure { Logger.error("XDM", "Error saving state", it) }
}

@Synchronized
fun loadState(id: Long, configDir: String, http: PoolingHttpClient, host: DownloadHost): Result<HttpTaskContext> {
    return AtomicIO.readTransacted<HttpTaskContext>("$id.state", configDir) { fs ->
        return Result.success(readContext(fs, host).apply { httpClient = http })
    }
}

@Synchronized
fun loadHlsState(id: Long, configDir: String, http: PoolingHttpClient, host: DownloadHost): Result<HlsTaskContext> {
    return AtomicIO.readTransacted<HlsTaskContext>("$id.state", configDir) { fs ->
        return Result.success(readHlsContext(fs, http, host))
    }
}

@Synchronized
fun loadDashState(id: Long, configDir: String, http: PoolingHttpClient, host: DownloadHost): Result<DashTaskContext> {
    return AtomicIO.readTransacted<DashTaskContext>("$id.state", configDir) { fs ->
        return Result.success(readDashContext(fs, http, host))
    }
}

@Synchronized
fun getTempFileFolder(id: Long, configDir: String): Result<Pair<String, String>> {
    return AtomicIO.readTransacted<Pair<String, String>>("$id.state", configDir) { fs ->
        fs.readLong() //Skip id
        return runCatching {
            Pair(fs.readUTF(), fs.readUTF())
        }
    }
}
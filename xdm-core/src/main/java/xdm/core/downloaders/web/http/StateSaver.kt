package xdm.core.downloaders.web.http

import xdm.core.downloaders.DownloadHost
import xdm.core.network.http.HeaderMap
import xdm.core.network.http.PoolingHttpClient
import xdm.core.util.AtomicIO
import xdm.core.util.Logger
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicBoolean
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
        out.writeUTF(tempFileName)
        out.writeBoolean(tempFileCreated.get())
        out.writeBoolean(diskError.get())
        out.writeUTF(tempFolder)
    }
}

private fun readContext(r: DataInputStream, http: PoolingHttpClient, host: DownloadHost): HttpTaskContext {
    return HttpTaskContext(
        id = r.readLong(),
        chunks = readChunks(r),
        init = AtomicBoolean(r.readBoolean()),
        totalSize = if (r.readBoolean()) r.readLong() else null,
        downloaded = AtomicLong(r.readLong()),
        url = r.readUTF(),
        contentType = if (r.readBoolean()) r.readUTF() else null,
        headers = readHeaders(r),
        cookie = if (r.readBoolean()) r.readUTF() else null,
        httpClient = http,
        stopFlag = AtomicBoolean(false),
        completed = AtomicBoolean(r.readBoolean()),
        tempFileName = r.readUTF(),
        tempFileCreated = AtomicBoolean(r.readBoolean()),
        diskError = AtomicBoolean(r.readBoolean()),
        downloadHost = host,
        tempFolder = r.readUTF(),
    ).apply { Logger.info(this) }
}

fun saveState(context: HttpTaskContext, configDir: String) {
    Logger.info(context)
    AtomicIO.writeTransacted("${context.id}.state", configDir) { fs ->
        writeContext(context, fs)
    }.onFailure { Logger.error("XDM", "Error saving state", it) }
}

fun loadState(id: Long, configDir: String, http: PoolingHttpClient, host: DownloadHost): Result<HttpTaskContext> {
    return AtomicIO.readTransacted<HttpTaskContext>("$id.state", configDir) { fs ->
        return Result.success(readContext(fs, http, host))
    }
}
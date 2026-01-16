//package xdm.core.downloaders.http
//
//import xdm.core.DownloadProgressListener
//import xdm.core.InteractiveCredentialProvider
//import xdm.core.XDMConstants
//import xdm.core.downloaders.*
//import xdm.core.net.getFileName
//import xdm.core.network.http.PoolingHttpClient
//import xdm.core.network.http.impl.HttpClientImpl2
//import xdm.core.util.Logger
//import xdm.core.util.MetadataStore
//import xdm.core.util.SerializationUtils
//import xdm.core.util.TransactedIO
//import java.io.DataInputStream
//import java.io.DataOutputStream
//import java.io.IOException
//
//class HttpDownloader(
//    id: Long,
//    tempFolder: String?,
//    override val sourceInfo: SourceInfo.HttpSourceInfo,
//    listener: DownloadProgressListener,
//    credentialProvider: InteractiveCredentialProvider
//) :
//    AbstractSegmentedDownloader(DownloaderType.Http, id, tempFolder, listener, credentialProvider) {
//    private val httpClient: PoolingHttpClient = HttpClientImpl2(100) // new PoolingHttpClientImpl(100);
//
//    @Synchronized
//    override fun createChannel(chunk: Chunk): AbstractChunkRetriever {
//        return HttpChunkRetriever(
//            chunk,
//            sourceInfo.url,
//            sourceInfo.headers,
//            sourceInfo.cookies,
//            length.get(),
//            this.httpClient
//        )
//    }
//
//    override fun getSize(): Long {
//        return this.size
//    }
//
//    override val type: Int
//        get() = XDMConstants.HTTP
//
//    override fun chunkConfirmed(c: Chunk?) {
//        if (c == null) return
//        try {
//            // logic
//            // if the response has html content type and no attachment
//            // no matter what is the target file extension, if any, will be changed to html.
//            // If the download
//            // has video conversion option, then conversion format will be removed.
//            // in case of having an attachment, attachment extension will be used
//
//            val hc = c.chunkRetriever as HttpChunkRetriever
//            super.getLastModifiedDate(c)
//            if (hc.isRedirected) {
//                sourceInfo.url = hc.getRedirectUrl()
//            }
//
//            val url = sourceInfo.url
//            val contentDisposition = hc.getHeader("content-disposition")
//            val contentType = hc.getHeader("content-type")
//
//            sourceInfo.fileName = getFileName(
//                sourceInfo.fileName,
//                sourceInfo.isKeepFileName,
//                url,
//                contentDisposition,
//                contentType
//            )
//        } finally {
//            MetadataStore.save(sourceInfo)
//        }
//    }
//
//    override fun cleanupConnections() {
//        xdm.core.util.Logger.info("XDM", "Cleanup done..")
//        httpClient.close()
//    }
//
//    override fun updateMetadataFinal(fileName: String?, folder: String?, totalBytes: Long) {
//        sourceInfo.fileName = fileName
//        sourceInfo.folder = folder
//        sourceInfo.fileSize = totalBytes
//        MetadataStore.save(this.sourceInfo)
//    }
//
//    override fun saveState() {
//        if (length.get() < 0) return
//        TransactedIO.write(
//            folder,
//            "state.txt",
//            { fs: DataOutputStream ->
//                fs.writeLong(length.get())
//                fs.writeLong(downloaded.get())
//                fs.writeInt(chunks!!.size)
//                for (i in chunks!!.indices) {
//                    val seg = chunks!![i]
//                    fs.writeLong(seg.id)
//                    fs.writeLong(seg.length)
//                    fs.writeLong(seg.startOffset)
//                    fs.writeLong(seg.downloaded)
//                }
//                SerializationUtils.writeNullable(this.lastModified, fs)
//            },
//            { e: IOException -> Logger.error("XDM", e.message, e) })
//    }
//
//    override fun restoreState(): Boolean {
//        chunks = ArrayList()
//        return TransactedIO.read(
//            folder,
//            "state.txt",
//            { reader: DataInputStream ->
//                length.set(reader.readLong())
//                downloaded.set(reader.readLong())
//                val chunkCount = reader.readInt()
//                for (i in 0..<chunkCount) {
//                    val cid = reader.readLong()
//                    val len = reader.readLong()
//                    val off = reader.readLong()
//                    val dwn = reader.readLong()
//                    val seg: Chunk = ChunkImpl(folder, cid, off, len, dwn)
//
//                    Logger.info(
//                        "XDM",
//                        "id: ${seg.id} length: ${seg.length} offset: ${seg.startOffset} download: ${seg.downloaded}"
//                    )
//
//                    chunks!!.add(seg)
//                }
//                this.lastModified = SerializationUtils.readStr(reader)
//            },
//            { e: IOException -> Logger.error("XDM", e.message, e) })
//    }
//}

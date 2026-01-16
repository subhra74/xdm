//package xdm.core.downloaders
//
//import org.slf4j.Logger
//import org.slf4j.LoggerFactory
//import xdm.core.Config
//import xdm.core.DownloadProgressListener
//import xdm.core.InteractiveCredentialProvider
//import xdm.core.constants.ErrorCode
//import xdm.core.downloaders.http.HttpChunkRetriever
//import xdm.core.util.CollectionUtils
//import xdm.core.util.StringUtils
//import xdm.core.util.XDMUtils
//import java.io.File
//import java.io.IOException
//import java.nio.channels.FileChannel
//import java.nio.file.Files
//import java.nio.file.StandardOpenOption
//import java.util.*
//import java.util.concurrent.atomic.AtomicBoolean
//import kotlin.math.min
//
//abstract class AbstractSegmentedDownloader protected constructor(
//    downloaderType: DownloaderType,
//    id: Long,
//    tempFolder: String?,
//    listener: DownloadProgressListener,
//    credentialProvider: InteractiveCredentialProvider
//) :
//    AbstractDownloader(id, downloaderType, listener, credentialProvider), ChunkUpdateListener {
//    private val init = AtomicBoolean(false)
//    private val minChunkSize: Int
//    private val assembleFinished = AtomicBoolean(false)
//    private var totalAssembled: Long = 0
//    private val downloadProgressData: DownloadProgressData
//
//    @JvmField
//    protected var chunks: ArrayList<Chunk>? = null
//    protected abstract fun cleanupConnections()
//
//    override fun start() {
//        logger.info("creating folder {}", folder)
//        File(folder).mkdirs()
//        this.chunks = ArrayList()
//        downloadProgressData.totalDownloadedBytes = 0L
//        try {
//            val c1: Chunk = ChunkImpl(this, folder)
//            // handle case of single dash stream
//            //      if (getMetadata() instanceof DashMetadata) {
//            //        c1.setTag("T1");
//            //      }
//            c1.length = -1
//            c1.startOffset = 0
//            c1.downloaded = 0
//            chunks!!.add(c1)
//            c1.download(this)
//        } catch (e: IOException) {
//            listener.downloadFailed(id, ErrorCode.RESUME_FAILED)
//        }
//    }
//
//    override fun resume() {
//        try {
//            stopFlag.set(false)
//            logger.info("Resuming")
//            if (!restoreState()) {
//                logger.info("Starting from beginning")
//                start()
//                return
//            }
//            downloadProgressData.totalDownloadedBytes = downloaded.get()
//            logger.info("Restore success")
//            init.set(true)
//            val c1 = findInactiveChunk()
//            if (c1 != null) {
//                resumeInternal(c1)
//            } else if (allFinished()) {
//                assembleAsync()
//            } else {
//                logger.error("Internal error: no inactive/incomplete chunk found while resuming!")
//            }
//        } catch (e: Exception) {
//            logger.error(e.message, e)
//            listener.downloadFailed(this.id, ErrorCode.RESUME_FAILED)
//        }
//    }
//
//    private fun resumeInternal(c1: Chunk) {
//        try {
//            c1.download(this)
//        } catch (e: Exception) {
//            logger.error("Error in Resume", e)
//            if (!stopFlag.get()) {
//                logger.error(e.message, e)
//                listener.downloadFailed(this.id, ErrorCode.RESUME_FAILED)
//            }
//        }
//    }
//
//    @Synchronized
//    @Throws(IOException::class)
//    private fun createChunk() {
//        if (stopFlag.get()) return
//        val activeCount = activeChunkCount
//        logger.info("active count: {}", activeCount)
//        if (activeCount == maxCount) {
//            return
//        }
//
//        var rem = maxCount - activeCount
//
//        rem -= retryFailedChunks(rem)
//
//        if (rem > 0) {
//            val c1 = findMaxChunk()
//            val c = splitChunk(c1)
//            if (c != null) {
//                logger.info("creating chunk {}", c.id)
//                chunks!!.add(c)
//                c.download(this)
//            }
//        }
//    }
//
//    private fun findMaxChunk(): Chunk? {
//        if (stopFlag.get()) return null
//        var size: Long = -1
//        var id: Long = -1
//        val time = System.currentTimeMillis()
//        for (c in chunks!!) {
//            if (c.isActive && time - c.lastTakeOverTime > MIN_TAKEOVER_DELAY) {
//                val rem = c.length - c.downloaded
//                if (rem > size) {
//                    id = c.id
//                    size = rem
//                }
//            }
//        }
//        if (size < minChunkSize) return null
//        return getById(id)
//    }
//
//    // merge c2 into c1
//    private fun mergeChunk(c1: Chunk, c2: Chunk) {
//        c1.length = c1.length + c2.length
//    }
//
//    private fun splitChunk(c: Chunk?): Chunk? {
//        if (c == null || stopFlag.get()) return null
//        val rem = c.length - c.downloaded
//        val offset = c.startOffset + c.length - rem / 2
//        val len = rem / 2
//        logger.info("Changing length from: {} {}", c.length, (c.length - rem / 2))
//        c.length = c.length - rem / 2
//        val c2: Chunk = ChunkImpl(this, folder)
//        // handle case of single dash stream
//        //    if (getMetadata() instanceof DashMetadata) {
//        //      c2.setTag("T1");
//        //    }
//        c2.length = len
//        c2.startOffset = offset
//        return c2
//    }
//
//    private fun findChunkToTakeOver(chunk: Chunk): Chunk? {
//        if (stopFlag.get()) return null
//        val offset = chunk.startOffset + chunk.length
//        var actualRange: Long = 0
//        if (chunk.chunkRetriever is HttpChunkRetriever) {
//            actualRange = (chunk.chunkRetriever as HttpChunkRetriever).actualRange
//        }
//        for (i in chunks!!.indices) {
//            val c = chunks!![i]
//            if (c.downloaded == 0L
//                && (!c.isFinished
//                        && (c.startOffset == offset
//                        && (c.startOffset + c.length) <= actualRange))
//            ) {
//                return c
//            }
//        }
//        return null
//    }
//
//    private fun finishDownload() {
//        finished.set(true)
//        updateStatus(0)
//        try {
//            assemble()
//            if (!assembleFinished.get()) {
//                throw IOException("Assemble failed")
//            }
//            logger.info("Download finished")
//            updateStatus(0)
//            listener.downloadFinished(this.id)
//        } catch (e: Exception) {
//            if (!stopFlag.get()) {
//                logger.error(e.message, e)
//                listener.downloadFailed(this.id, ErrorCode.ERR_ASM_FAILED)
//            }
//        } finally {
//            cleanupConnections()
//        }
//    }
//
//    @Synchronized
//    @Throws(IOException::class)
//    private fun onComplete(id: Long): Boolean {
//        if (allFinished() || length.get() < 0) {
//            // finish
//            finishDownload()
//            return true
//        }
//        val chunk = getById(id)
//        logger.info(
//            "Complete: {} downloaded: {} Length: {}",
//            chunk!!.id,
//            chunk.downloaded,
//            chunk.length
//        )
//        val nextNeedyChunk = findChunkToTakeOver(chunk)
//        if (nextNeedyChunk != null) {
//            logger.info("Needy chunk found!!!")
//            logger.info("Stopping: {}", nextNeedyChunk.id)
//            nextNeedyChunk.stop()
//            chunks!!.remove(nextNeedyChunk)
//            mergeChunk(chunk, nextNeedyChunk)
//            chunk.lastTakeOverTime = System.currentTimeMillis()
//            createChunk()
//            return false
//        }
//        clearChannel(chunk)
//        createChunk()
//        return true
//    }
//
//    @Synchronized
//    @Throws(IOException::class)
//    override fun chunkInitiated(id: Long) {
//        if (stopFlag.get()) return
//        if (!init.get()) {
//            val c = getById(id)
//            length.set(c!!.length)
//            init.set(true)
//            logger.info("Download size: {}", this.length)
//            this.getLastModifiedDate(c)
//            saveState()
//            chunkConfirmed(c)
//            listener.downloadConfirmed(this.id)
//        }
//        if (length.get() > 0) {
//            createChunk()
//        }
//    }
//
//    @Synchronized
//    @Throws(IOException::class)
//    override fun chunkComplete(id: Long): Boolean {
//        if (finished.get()) {
//            return true
//        }
//
//        if (stopFlag.get()) {
//            return true
//        }
//
//        saveState()
//
//        return onComplete(id)
//    }
//
//    override fun chunkUpdated(id: Long, bytes: Int) {
//        if (stopFlag.get()) return
//        downloaded.getAndAdd(bytes.toLong())
//        this.throttle()
//        val now = System.currentTimeMillis()
//        if (now - lastSaved > 5000) {
//            synchronized(this) {
//                saveState()
//            }
//            lastSaved = now
//        }
//        updateStatus(bytes)
//        if (now - lastUpdated > 1000) {
//            lastUpdated = now
//            synchronized(this) {
//                val activeCount = activeChunkCount
//                if (activeCount < maxCount) {
//                    val rem = maxCount - activeCount
//                    try {
//                        retryFailedChunks(rem)
//                    } catch (e: Exception) {
//                        logger.info(e.message, e)
//                    }
//                }
//            }
//        }
//    }
//
//    @Throws(IOException::class)
//    private fun copyBytes(inChannel: FileChannel, outChannel: FileChannel, limit: Long) {
//        val block1M: Long = 1048576
//        var pos: Long = 0
//        var rem = if (limit > 0) limit else inChannel.size()
//        while (!stopFlag.get() && rem > 0) {
//            val x = inChannel.transferTo(pos, min(block1M.toDouble(), rem.toDouble()).toLong(), outChannel)
//            rem -= x
//            pos += x
//            totalAssembled += x
//            val now = System.currentTimeMillis()
//            if (now - lastUpdated > 3000) {
//                updateStatus(0)
//                lastUpdated = now
//            }
//        }
//        if (!stopFlag.get() && limit > 0 && pos != limit) {
//            logger.error("Chunk file is shorter than chunk size, possible file corruption")
//            throw IllegalArgumentException("Assemble EOF")
//        }
//    }
//
//    @Throws(IOException::class)
//    private fun copyChunks(outChannel: FileChannel) {
//        for (i in chunks!!.indices) {
//            logger.info("Assembling chunk: {}", i)
//            val c = chunks!![i]
//            FileChannel.open(
//                File(folder, c.id.toString()).toPath(), StandardOpenOption.READ
//            ).use { inChannel ->
//                copyBytes(inChannel, outChannel, c.length)
//            }
//            if (stopFlag.get()) {
//                return
//            }
//        }
//    }
//
//    //  private void joinChunks(final ByteBuffer buffer, final FileChannel outChannel)
//    //      throws IOException {
//    //    for (int i = 0; i < chunks.size(); i++) {
//    //      logger.info("chunk {} {}", i, stopFlag);
//    //      Chunk c = chunks.get(i);
//    //      try (FileChannel inChannel =
//    //          FileChannel.open(new File(folder, c.getId()).toPath(), StandardOpenOption.READ)) {
//    //        long rem = c.getLength();
//    //        while (!stopFlag.get()) {
//    //          int x = rem > 0 ? (int) Math.min(rem, buffer.capacity()) : buffer.capacity();
//    //          buffer.limit(x);
//    //          int r = inChannel.read(buffer);
//    //          if (stopFlag.get()) {
//    //            return;
//    //          }
//    //          if (r == -1) {
//    //            if (length.get() > 0) {
//    //              throw new IllegalArgumentException("Assemble EOF");
//    //            } else {
//    //              break;
//    //            }
//    //          }
//    //          buffer.flip();
//    //          outChannel.write(buffer);
//    //          buffer.clear();
//    //          if (stopFlag.get()) {
//    //            return;
//    //          }
//    //          if (length.get() > 0) {
//    //            rem -= r;
//    //            if (rem == 0) break;
//    //          }
//    //          totalAssembled += r;
//    //          long now = System.currentTimeMillis();
//    //          if (now - lastUpdated > 1000) {
//    //            updateStatus(0);
//    //            lastUpdated = now;
//    //          }
//    //        }
//    //      }
//    //    }
//    //  }
//    @Throws(IOException::class)
//    private fun assemble() {
//        logger.info("Assembling start...")
//        totalAssembled = 0L
//        assembling = true
//        assembleFinished.set(false)
//        val outputFolder = outputFolder
//        val outFileName = UUID.randomUUID().toString() + "_" + outputFileName
//        XDMUtils.mkdirs(outputFolder)
//        val outFile = File(outputFolder, outFileName)
//        val ffOutFile: File? = null
//        try {
//            if (stopFlag.get()) return
//            logger.info("assembling... ")
//            Collections.sort(chunks, SegmentComparator())
//            FileChannel.open(
//                outFile.toPath(),
//                CollectionUtils.setOf(
//                    StandardOpenOption.CREATE,
//                    StandardOpenOption.WRITE,
//                    StandardOpenOption.TRUNCATE_EXISTING
//                )
//            ).use { outChannel ->
//                copyChunks(outChannel)
//                if (stopFlag.get()) {
//                    return
//                }
//            }
//            setLastModifiedDate(outFile)
//            updateStatus(0)
//            // delete the original file if exists and rename the temp file to original
//            val finalFileName = outputFileName
//            val realFile = File(outputFolder, finalFileName)
//            delete(realFile)
//            renameTo(outFile, realFile)
//            setLastModifiedDate(outFile)
//            updateMetadataFinal(finalFileName, outputFolder, totalAssembled)
//            assembleFinished.set(true)
//        } catch (e: Exception) {
//            throw IOException(e)
//        } finally {
//            if (!assembleFinished.get()) {
//                delete(outFile)
//                delete(ffOutFile)
//            }
//        }
//    }
//
//    protected abstract fun updateMetadataFinal(fileName: String?, folder: String?, totalBytes: Long)
//
//    private fun delete(file: File?) {
//        if (file != null) {
//            try {
//                Files.deleteIfExists(file.toPath())
//            } catch (ex: Exception) {
//                logger.debug("delete failed")
//            }
//        }
//    }
//
//    private fun renameTo(f1: File, f2: File) {
//        if (!f1.renameTo(f2)) {
//            logger.error("Unable to rename")
//        }
//    }
//
//    override fun stop() {
//        speedLimiter.wakeIfSleeping()
//        stopFlag.set(true)
//        saveState()
//        for (chunk in chunks!!) {
//            chunk.stop()
//        }
//        listener.downloadStopped(id)
//        cleanupConnections()
//    }
//
//    protected abstract fun saveState()
//
//    //  private void saveState() {
//    //    if (length.get() < 0) return;
//    //    StringBuilder sb = new StringBuilder();
//    //    sb.append(this.length + "\n");
//    //    sb.append(downloaded + "\n");
//    //    sb.append(chunks.size() + "\n");
//    //    for (int i = 0; i < chunks.size(); i++) {
//    //      Chunk seg = chunks.get(i);
//    //      sb.append(seg.getId() + "\n");
//    //      sb.append(seg.getLength() + "\n");
//    //      sb.append(seg.getStartOffset() + "\n");
//    //      sb.append(seg.getDownloaded() + "\n");
//    //    }
//    //    if (!StringUtils.isNullOrEmptyOrBlank(lastModified)) {
//    //      sb.append(this.lastModified + "\n");
//    //    }
//    //    try {
//    //      File tmp = new File(folder, System.currentTimeMillis() + ".tmp");
//    //      File out = new File(folder, "state.txt");
//    //      try (FileOutputStream fs = new FileOutputStream(tmp)) {
//    //        fs.write(sb.toString().getBytes());
//    //      }
//    //      delete(out);
//    //      renameTo(tmp, out);
//    //    } catch (Exception e) {
//    //      logger.error(e.getMessage(), e);
//    //    }
//    //  }
//    protected abstract fun restoreState(): Boolean
//
//    protected abstract fun chunkConfirmed(c: Chunk?)
//
//    override fun shouldCleanup(): Boolean {
//        return assembleFinished.get()
//    }
//
//    override val segmentDetails: SegmentDetails
//        get() = downloadProgressData.segmentDetails!!
//
//    var lastUpdatePosted: Long = 0
//
//    init {
//        this.folder = File(tempFolder, id.toString()).absolutePath
//        length.set(-1)
//        this.maxCount = Config.getInstance().maxSegments
//        this.minChunkSize = Config.getInstance().minSegmentSize
//        this.eta = -1
//        this.downloadProgressData = DownloadProgressData(this, 0)
//    }
//
//    private fun updateStatus(bytes: Int) {
//        try {
//            val now = System.currentTimeMillis()
//            if (converting) {
//                progress.set(XDMUtils.clamp(this.convertPrg, 0, 100))
//            } else if (this.assembling) {
//                val len = if (length.get() > 0) length.get() else downloaded.get()
//                progress.set(XDMUtils.clamp(((totalAssembled * 100) / len).toInt(), 0, 100))
//            } else {
//                downloadProgressData.updateDownloadInfo(bytes.toLong(), this.chunks)
//                progress.set(
//                    XDMUtils.clamp((downloaded.get() * 100 / length.get()).toInt(), 0, 100)
//                )
//                this.eta = downloadProgressData.eta
//                this.downloadSpeed = downloadProgressData.downloadSpeed
//            }
//            if (now - this.lastUpdatePosted > 1000) {
//                listener.downloadUpdated(id)
//                this.lastUpdatePosted = now
//            }
//        } catch (e: Exception) {
//            logger.error(e.message, e)
//        }
//    }
//
//    private fun assembleAsync() {
//        Thread {
//            finished.set(true)
//            try {
//                assemble()
//                if (!assembleFinished.get()) {
//                    throw IOException("Assemble not finished successfully")
//                }
//                logger.error("********Download finished*********")
//                updateStatus(0)
//                cleanup()
//                listener.downloadFinished(id)
//            } catch (e: Exception) {
//                if (!stopFlag.get()) {
//                    logger.error(e.message, e)
//                    listener.downloadFailed(id, ErrorCode.ERR_ASM_FAILED)
//                }
//            }
//        }
//            .start()
//    }
//
//    @Synchronized
//    override fun chunkFailed(id: Long, reason: String) {
//        if (stopFlag.get()) return
//        // If all chunks are inactive and return the error or if any chunk is active then return 0
//        val err = chunkError ?: return
//        if (finished.get()) {
//            return
//        }
//        listener.downloadFailed(this.id, getFinalError(err))
//        cleanupConnections()
//        logger.error("failed")
//    }
//
//    @Synchronized
//    override fun promptCredential(msg: String, proxy: Boolean): Boolean {
//        return credentialProvider.promptCredential(id, msg, proxy)
//    }
//
//    @Synchronized
//    @Throws(IOException::class)
//    protected fun retryFailedChunks(rem: Int): Int {
//        if (stopFlag.get()) return 0
//        var count = 0
//        var totalInactive = findTotalInactiveChunk()
//        logger.info("Total inactive chunks: {}", totalInactive)
//
//        if (totalInactive > rem) {
//            totalInactive = rem
//        }
//        if (totalInactive > 0) {
//            while (totalInactive > 0) {
//                val c = findInactiveChunk()
//                if (c != null) {
//                    c.download(this)
//                    count++
//                }
//                totalInactive--
//            }
//        }
//        return count
//    }
//
//    protected fun findInactiveChunk(): Chunk? {
//        if (stopFlag.get()) return null
//        for (c in chunks!!) {
//            if (c.isFinished || c.isActive) continue
//            return c
//        }
//        return null
//    }
//
//    protected fun findTotalInactiveChunk(): Int {
//        var count = 0
//        for (c in chunks!!) {
//            if (c.isFinished || c.isActive) continue
//            count++
//        }
//        return count
//    }
//
//    override fun getActiveChunkCount(): Int {
//        var count = 0
//        for (chunk in chunks!!) {
//            if (chunk.isActive) {
//                count++
//            }
//        }
//        return count
//    }
//
//    private val chunkError: ErrorCode?
//        get() {
//            if (chunks!!.isEmpty()) {
//                return null
//            }
//            for (chunk in chunks!!) {
//                if (chunk.isActive) {
//                    return null
//                }
//            }
//            return chunks!![0].errorCode
//        }
//
//    private fun getFinalError(err: ErrorCode): ErrorCode {
//        val errorCode: ErrorCode
//        if (err == ErrorCode.ERR_INVALID_RESP) {
//            errorCode = if (downloaded.get() > 0) {
//                if (length.get() > 0) {
//                    if (chunks!!.size > 1) {
//                        ErrorCode.ERR_SESSION_EXPIRED
//                    } else {
//                        ErrorCode.ERR_NO_RESUME
//                    }
//                } else {
//                    ErrorCode.ERR_NO_RESUME
//                }
//            } else {
//                ErrorCode.ERR_INVALID_RESP
//            }
//        } else {
//            logger.info("Setting final error code: {}", err)
//            errorCode = err
//        }
//        return errorCode
//    }
//
//    protected fun allFinished(): Boolean {
//        if (!chunks!!.isEmpty()) {
//            for (chunk in chunks!!) {
//                if (!chunk.isFinished) {
//                    return false
//                }
//            }
//            return true
//        } else {
//            return false
//        }
//    }
//
//    protected fun getById(id: Long): Chunk? {
//        for (chunk in chunks!!) {
//            if (chunk.id == id) {
//                return chunk
//            }
//        }
//        return null
//    }
//
//    fun getLastModifiedDate(c: Chunk) {
//        if (StringUtils.isNullOrEmpty(lastModified)) {
//            try {
//                if (c.chunkRetriever is HttpChunkRetriever) {
//                    this.lastModified =
//                        (c.chunkRetriever as HttpChunkRetriever).getHeader("last-modified")
//                }
//            } catch (e: Exception) {
//                logger.error("Error getLastModifiedDate", e)
//            }
//        }
//    }
//
//    protected fun clearChannel(s: Chunk?) {
//        s?.clearChannel()
//    }
//
//    @Synchronized
//    override fun synchronize() {
//    }
//
//    companion object {
//        private val logger: Logger = LoggerFactory.getLogger(AbstractSegmentedDownloader::class.java)
//        private const val MIN_TAKEOVER_DELAY = 5000
//    }
//}

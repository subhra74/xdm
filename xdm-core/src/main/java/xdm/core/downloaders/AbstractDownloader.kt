//package xdm.core.downloaders
//
//import xdm.core.Config
//import xdm.core.DownloadProgressListener
//import xdm.core.InteractiveCredentialProvider
//import xdm.core.util.HttpDateParser
//import xdm.core.util.Logger
//import java.io.File
//import java.io.IOException
//import java.nio.file.Files
//import java.nio.file.Path
//import java.nio.file.Paths
//import java.util.concurrent.atomic.AtomicBoolean
//import java.util.concurrent.atomic.AtomicInteger
//import java.util.concurrent.atomic.AtomicLong
//
//abstract class AbstractDownloader protected constructor(
//    protected val id: Long,
//    val downloaderType: DownloaderType,
//    protected val listener: DownloadProgressListener,
//    protected var credentialProvider: InteractiveCredentialProvider
//) {
//    val stopFlag: AtomicBoolean = AtomicBoolean(false)
//    val length: AtomicLong = AtomicLong()
//    var folder: String? = null
//    val finished: AtomicBoolean = AtomicBoolean(false)
//    var maxCount: Int = 8
//    val speedLimiter: SpeedLimiter = SpeedLimiter(this)
//    val downloaded: AtomicLong = AtomicLong(0)
//    val progress: AtomicInteger = AtomicInteger(0)
//    var lastUpdated: Long = 0
//    var lastSaved: Long = 0
//    var assembling: Boolean = false
//    var downloadSpeed: Float = 0f
//    var eta: Long = 0
//    var outputFormat: Int = 0
//    var converting: Boolean = false
//    var convertPrg: Int = 0
//    var lastModified: String? = null
//
//    abstract fun start()
//
//    abstract fun stop()
//
//    abstract fun resume()
//
//    abstract val type: Int
//
//    val size: Long
//        get() = length.get()
//
//    fun getDownloaded(): Long {
//        return downloaded.get()
//    }
//
//    abstract val sourceInfo: SourceInfo?
//
//    abstract val segmentDetails: SegmentDetails?
//
//    @Synchronized
//    fun cleanup() {
//        Logger.info("XDM", "Cleaning up temp files")
//        if (folder == null) {
//            return
//        }
//        try {
//            val folderPath = Paths.get(folder!!)
//            if (!Files.exists(folderPath)) {
//                return
//            }
//            Files.walk(folderPath).sorted(Comparator.reverseOrder()).use { files ->
//                files.forEach { f: Path? ->
//                    try {
//                        Files.delete(f)
//                        Logger.info("XDM", "Successfully delete file : $f")
//                    } catch (e: IOException) {
//                        Logger.error("XDM", "Error deleting temp file: $f ", e)
//                    }
//                }
//            }
//        } catch (ex: Exception) {
//            Logger.error("XDM", "Error cleaning up temp files", ex)
//        }
//    }
//
//    protected val outputFolder: String
//        get() = listener.getOutputFolder(id)
//
//    protected fun getBackupFile(folder: String): File? {
//        val f = File(folder)
//        val files = f.listFiles()
//        if (files == null || files.isEmpty()) return null
//        for (file in files) {
//            if (file.name.endsWith(".bak")) {
//                return file
//            }
//        }
//        return null
//    }
//
//    fun setLastModifiedDate(outFile: File) {
//        if (Config.getInstance().isFetchTs) {
//            try {
//                val date = HttpDateParser.parseHttpDate(this.lastModified)
//                if (date != null) {
//                    val modified = outFile.setLastModified(date.time)
//                    Logger.info("XDM", "Updated last modified: $modified")
//                }
//            } catch (e: Exception) {
//                Logger.error("XDM", "Error setLastModifiedDate", e)
//            }
//        }
//    }
//
//    fun throttle() {
//        speedLimiter.throttleIfNeeded()
//    }
//
//    protected val outputFileName: String
//        get() = listener.getOutputFileName(id)
//}

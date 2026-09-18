package xdm.app

import xdm.core.downloaders.DownloadType
import xdm.core.util.AtomicIO
import xdm.core.util.Logger
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

enum class RecordStatus {
    DOWNLOADING,
    FINISHED,
    READY,
    ERROR,
    PAUSED,
    ASSEMBLING
}

data class DbRecord(
    var id: Long,
    var size: Long,
    var downloaded: Long,
    var progress: Int,
    var date: Long,
    var fileName: String,
    var eta: Long,
    var speed: Float,
    var selected: Boolean,
    var status: RecordStatus,
    val downloadType: DownloadType,
)

class AppDB(private val configDir: String) {
    private val activeFileName: String = "active-downloads.dat"
    private val pausedFileName: String = "paused-downloads.dat"
    private val finishedFileName: String = "finished-downloads.dat"
    private var lastActiveCount = 0
    private var lastFinishedCount = 0
    private var lastPausedCount = 0
    private val records = Collections.synchronizedList(ArrayList<DbRecord>())
    private val indexMap = ConcurrentHashMap<Long, Int>()

    @Synchronized
    fun indexById(id: Long): Int? = indexMap[id]

    @Synchronized
    fun getByIndex(index: Int): DbRecord = records[index]

    val size: Int
        @Synchronized get() = records.size

    /** Synchronized with [removeItem]/[removeWhere], which rebuild [indexMap] after shifting rows. */
    @Synchronized
    fun getById(id: Long): DbRecord? = indexMap[id]?.let { records.getOrNull(it) }?.takeIf { it.id == id }

    @Synchronized
    fun addActive(rec: DbRecord) {
        val len = records.size
        records.add(rec)
        indexMap[rec.id] = len
    }

    @Synchronized
    fun removeItem(id: Long) {
        var savePaused = false
        var saveFinished = false
        var saveActive = false
        for (rec in records) {
            if (rec.id == id) {
                if (rec.status == RecordStatus.PAUSED) {
                    savePaused = true
                } else if (rec.status == RecordStatus.FINISHED) {
                    saveFinished = true
                } else {
                    // READY, DOWNLOADING, ASSEMBLING and ERROR are all kept in the active list.
                    saveActive = true
                }
                Logger.info("Item removed: $rec")
                records.remove(rec)
                break
            }
        }
        indexMap.remove(id)
        for ((i, r) in records.withIndex()) {
            indexMap[r.id] = i
        }
        if (savePaused) {
            savePausedRecords()
        }
        if (saveActive) {
            saveActiveRecords()
        }
        if (saveFinished) {
            saveFinishedRecords()
        }
    }

    @Synchronized
    fun loadRecords() {
        loadActiveRecords()
        loadPausedRecords()
        loadFinishedRecords()
        for ((i, r) in records.withIndex()) {
            indexMap[r.id] = i
        }
    }

    @Synchronized
    fun saveActiveRecords() {
        val res = AtomicIO.writeTransacted(activeFileName, configDir) { fs ->
            synchronized(records) {
                val count =
                    records.count { it.status != RecordStatus.FINISHED && it.status != RecordStatus.PAUSED }
                fs.writeInt(count)
                for (value in records.filter {
                    it.status != RecordStatus.FINISHED && it.status != RecordStatus.PAUSED
                }) {
                    writeRecord(value, fs)
                }
            }
        }
        //TODO: Check errors
    }

    private fun loadRecordsFromStream(fs: DataInputStream, paused: Boolean): Int {
        val c = fs.readInt()
        for (x in 0..<c) {
            val rec = readRecord(fs, paused)
            records.add(rec)
        }
        return c
    }

    private fun loadActiveRecords() {
        val res = AtomicIO.readTransacted(activeFileName, configDir) { fs ->
            lastActiveCount = loadRecordsFromStream(fs, paused = true)
        }
        //TODO: Check errors
    }

    @Synchronized
    fun savePausedRecords() {
        val res = AtomicIO.writeTransacted(pausedFileName, configDir) { fs ->
            synchronized(records) {
                val count =
                    records.count { it.status == RecordStatus.PAUSED }
                fs.writeInt(count)
                for (value in records.filter {
                    it.status == RecordStatus.PAUSED
                }) {
                    writeRecord(value, fs)
                }
            }
        }
        //TODO: Check errors
    }

    private fun loadPausedRecords() {
        val res = AtomicIO.readTransacted(pausedFileName, configDir) { fs ->
            lastPausedCount = loadRecordsFromStream(fs, paused = true)
        }
        //TODO: Check errors
    }

    @Synchronized
    fun saveFinishedRecords() {
        val res = AtomicIO.writeTransacted(finishedFileName, configDir) { fs ->
            synchronized(records) {
                val count =
                    records.count { it.status == RecordStatus.FINISHED }
                fs.writeInt(count)
                for (value in records.filter {
                    it.status == RecordStatus.FINISHED
                }) {
                    writeRecord(value, fs)
                }
            }
        }
        //TODO: Check errors
    }

    private fun loadFinishedRecords() {
        val res = AtomicIO.readTransacted(finishedFileName, configDir) { fs ->
            lastFinishedCount = loadRecordsFromStream(fs, paused = false)
        }
        //TODO: Check errors
    }

    private fun writeRecord(rec: DbRecord, writer: DataOutputStream) {
        writer.writeLong(rec.id)
        writer.writeLong(rec.size)
        writer.writeLong(rec.downloaded)
        writer.writeInt(rec.progress)
        writer.writeLong(rec.date)
        writer.writeUTF(rec.fileName)
        writer.writeUTF(rec.downloadType.toString())
    }

    private fun readRecord(r: DataInputStream, paused: Boolean = true): DbRecord {
        return DbRecord(
            id = r.readLong(),
            size = r.readLong(),
            downloaded = r.readLong(),
            progress = r.readInt(),
            date = r.readLong(),
            fileName = r.readUTF(),
            eta = 0,
            speed = 0.0f,
            selected = false,
            status = if (paused) RecordStatus.PAUSED else RecordStatus.FINISHED,
            downloadType = DownloadType.valueOf(r.readUTF()),
        )
    }

    /**
     * Removes every record matching [predicate], re-indexes and saves all three lists. Returns the
     * removed records. Only removes rows; the caller deletes their files.
     */
    @Synchronized
    fun removeWhere(predicate: (DbRecord) -> Boolean): List<DbRecord> {
        val removed = synchronized(records) {
            val matching = records.filter(predicate)
            records.removeAll(matching)
            matching
        }
        indexMap.clear()
        for ((i, r) in records.withIndex()) {
            indexMap[r.id] = i
        }
        savePausedRecords()
        saveActiveRecords()
        saveFinishedRecords()
        return removed
    }
}

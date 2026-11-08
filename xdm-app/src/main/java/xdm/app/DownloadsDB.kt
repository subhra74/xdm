package xdm.app

import xdm.core.downloaders.DownloadType
import xdm.core.downloaders.web.batch.loadBatchState
import xdm.core.util.AtomicIO
import xdm.core.util.Logger
import xdm.core.util.MovePhase
import xdm.core.util.TagReader
import xdm.core.util.TagWriter
import xdm.core.util.TaggedFile
import xdm.core.util.byStableId
import xdm.manager.DbRecord
import xdm.manager.DownloadStore
import xdm.manager.RecordStatus
import java.io.DataInputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

// Field numbers of the download list files (see TaggedFile). Never reuse or retype a number.
private const val F_RECORD = 1

// DbRecord
private const val R_ID = 1
private const val R_SIZE = 2
private const val R_DOWNLOADED = 3
private const val R_PROGRESS = 4
private const val R_DATE = 5
private const val R_FILE_NAME = 6
private const val R_DOWNLOAD_TYPE = 7
private const val R_FAILED = 8
private const val R_CATEGORY_ID = 9
/** Retired: was a per-download "ignore certificate errors" flag. Skipped when read; never reuse 10. */
@Suppress("unused")
private const val R_RETIRED_IGNORE_CERT_ERRORS = 10

/**
 * The desktop's download list: every record in memory, saved as three tagged files by state (active,
 * paused, finished), each rewritten whole when one of its records changes. Also the Swing list's model:
 * rows are read by index on the EDT.
 */
class AppDB(private val configDir: String) : DownloadStore {
    private val activeFileName: String = "active-downloads.dat"
    private val pausedFileName: String = "paused-downloads.dat"
    private val finishedFileName: String = "finished-downloads.dat"
    private var lastActiveCount = 0
    private var lastFinishedCount = 0
    private var lastPausedCount = 0
    private val records = Collections.synchronizedList(ArrayList<DbRecord>())
    private val indexMap = ConcurrentHashMap<Long, Int>()

    /** The list file each record was last saved in, so a status change also rewrites the one it left. */
    private val savedIn = ConcurrentHashMap<Long, ListFile>()

    private enum class ListFile { ACTIVE, PAUSED, FINISHED }

    private fun listFileOf(status: RecordStatus): ListFile = when {
        status.isStopped -> ListFile.PAUSED
        status == RecordStatus.FINISHED -> ListFile.FINISHED
        // READY, DOWNLOADING, ASSEMBLING and MOVING stay in the active list.
        else -> ListFile.ACTIVE
    }

    private fun save(file: ListFile) = when (file) {
        ListFile.ACTIVE -> saveActiveRecords()
        ListFile.PAUSED -> savePausedRecords()
        ListFile.FINISHED -> saveFinishedRecords()
    }

    @Synchronized
    fun indexById(id: Long): Int? = indexMap[id]

    @Synchronized
    fun getByIndex(index: Int): DbRecord = records[index]

    /** Like [getByIndex] but null when [index] is out of range (rows can be removed on other threads). */
    @Synchronized
    fun getOrNull(index: Int): DbRecord? = records.getOrNull(index)

    val size: Int
        @Synchronized get() = records.size

    /** How many records match [predicate]. */
    @Synchronized
    fun count(predicate: (DbRecord) -> Boolean): Int = synchronized(records) { records.count(predicate) }

    /** The records matching [predicate], copied out under the list's lock. */
    override fun filter(predicate: (DbRecord) -> Boolean): List<DbRecord> = synchronized(records) { records.filter(predicate) }

    /** A copy of the records matching [predicate], taken under the lock. */
    @Synchronized
    fun findAll(predicate: (DbRecord) -> Boolean): List<DbRecord> = synchronized(records) { records.filter(predicate) }

    /** Synchronized with [removeItem]/[removeWhere], which rebuild [indexMap] after shifting rows. */
    @Synchronized
    override fun getById(id: Long): DbRecord? = indexMap[id]?.let { records.getOrNull(it) }?.takeIf { it.id == id }

    @Synchronized
    fun addActive(rec: DbRecord) {
        val len = records.size
        records.add(rec)
        indexMap[rec.id] = len
    }

    @Synchronized
    override fun add(rec: DbRecord) {
        addActive(rec)
        update(rec)
    }

    /** Rewrites the list file [rec] belongs in now, and the one it was last saved in when that differs. */
    @Synchronized
    override fun update(rec: DbRecord) {
        val now = listFileOf(rec.status)
        val before = savedIn.put(rec.id, now)
        save(now)
        if (before != null && before != now) save(before)
    }

    @Synchronized
    override fun remove(id: Long): Int? {
        val index = indexMap[id] ?: return null
        val current = getById(id)?.let { listFileOf(it.status) }
        val left = savedIn.remove(id)
        removeItem(id)
        // Its status may have changed since it was last saved; the file it is still in loses it too.
        if (left != null && left != current) save(left)
        return index
    }

    @Synchronized
    fun removeItem(id: Long) {
        var savePaused = false
        var saveFinished = false
        var saveActive = false
        for (rec in records) {
            if (rec.id == id) {
                if (rec.status.isStopped) {
                    savePaused = true
                } else if (rec.status == RecordStatus.FINISHED) {
                    saveFinished = true
                } else {
                    // READY, DOWNLOADING, ASSEMBLING and MOVING stay in the active list.
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
            if (r.downloadType == DownloadType.Batch && r.status == RecordStatus.FINISHED) {
                r.failedFiles = loadBatchState(r.id, configDir).getOrNull()?.failedCount ?: 0
            }
        }
    }

    @Synchronized
    fun saveActiveRecords() {
        val res = AtomicIO.writeTransacted(activeFileName, configDir) { fs ->
            synchronized(records) {
                TaggedFile.write(fs) {
                    for (value in records) {
                        if (value.status != RecordStatus.FINISHED && !value.status.isStopped) {
                            message(F_RECORD) { writeRecord(value, this) }
                        }
                    }
                }
            }
        }
        //TODO: Check errors
    }

    private fun loadRecordsFromStream(fs: DataInputStream, paused: Boolean, file: ListFile): Int {
        val r = TaggedFile.read(fs)
        var c = 0
        while (true) {
            when (r.next()) {
                -1 -> return c
                F_RECORD -> {
                    // One unreadable record (say, a download type a newer build added) is skipped,
                    // not the whole list.
                    val rec = r.message()
                    runCatching { readRecord(rec, paused) }
                        .onSuccess { if (it != null) { records.add(it); savedIn[it.id] = file; c++ } }
                        .onFailure { Logger.error("Skipping an unreadable download record: $it") }
                }
                else -> r.skip()
            }
        }
    }

    private fun loadActiveRecords() {
        val res = AtomicIO.readTransacted(activeFileName, configDir) { fs ->
            lastActiveCount = loadRecordsFromStream(fs, paused = true, ListFile.ACTIVE)
        }
        //TODO: Check errors
    }

    @Synchronized
    fun savePausedRecords() {
        val res = AtomicIO.writeTransacted(pausedFileName, configDir) { fs ->
            synchronized(records) {
                TaggedFile.write(fs) {
                    for (value in records) {
                        if (value.status.isStopped) {
                            message(F_RECORD) { writeRecord(value, this) }
                        }
                    }
                }
            }
        }
        //TODO: Check errors
    }

    private fun loadPausedRecords() {
        val res = AtomicIO.readTransacted(pausedFileName, configDir) { fs ->
            lastPausedCount = loadRecordsFromStream(fs, paused = true, ListFile.PAUSED)
        }
        //TODO: Check errors
    }

    @Synchronized
    fun saveFinishedRecords() {
        val res = AtomicIO.writeTransacted(finishedFileName, configDir) { fs ->
            synchronized(records) {
                TaggedFile.write(fs) {
                    for (value in records) {
                        if (value.status == RecordStatus.FINISHED) {
                            message(F_RECORD) { writeRecord(value, this) }
                        }
                    }
                }
            }
        }
        //TODO: Check errors
    }

    private fun loadFinishedRecords() {
        val res = AtomicIO.readTransacted(finishedFileName, configDir) { fs ->
            lastFinishedCount = loadRecordsFromStream(fs, paused = false, ListFile.FINISHED)
        }
        //TODO: Check errors
    }

    private fun writeRecord(rec: DbRecord, w: TagWriter) {
        w.long(R_ID, rec.id)
        w.long(R_SIZE, rec.size)
        w.long(R_DOWNLOADED, rec.downloaded)
        w.int(R_PROGRESS, rec.progress)
        w.long(R_DATE, rec.date)
        w.string(R_FILE_NAME, rec.fileName)
        w.int(R_DOWNLOAD_TYPE, rec.downloadType.id)
        w.bool(R_FAILED, rec.status == RecordStatus.ERROR)
        w.stringOrNull(R_CATEGORY_ID, rec.categoryId)
    }

    /** Null for a record without an id or with a download type this build does not know. */
    private fun readRecord(r: TagReader, paused: Boolean = true): DbRecord? {
        var id: Long? = null
        var size = 0L
        var downloaded = 0L
        var progress = 0
        var date = 0L
        var fileName = ""
        var downloadType: DownloadType? = null
        var failed = false
        var categoryId: String? = null
        while (true) {
            when (r.next()) {
                -1 -> break
                R_ID -> id = r.long()
                R_SIZE -> size = r.long()
                R_DOWNLOADED -> downloaded = r.long()
                R_PROGRESS -> progress = r.int()
                R_DATE -> date = r.long()
                R_FILE_NAME -> fileName = r.string()
                R_DOWNLOAD_TYPE -> downloadType = DownloadType.entries.toTypedArray().byStableId(r.int())
                R_FAILED -> failed = r.bool()
                R_CATEGORY_ID -> categoryId = r.string()
                else -> r.skip()
            }
        }
        return DbRecord(
            id = id ?: return null,
            size = size,
            downloaded = downloaded,
            progress = progress,
            date = date,
            fileName = fileName,
            eta = 0,
            speed = 0.0f,
            selected = false,
            // A download that was running when XDM exited comes back paused.
            status = when {
                !paused -> RecordStatus.FINISHED
                failed -> RecordStatus.ERROR
                else -> RecordStatus.PAUSED
            },
            downloadType = downloadType ?: return null,
            categoryId = categoryId,
        )
    }

    /**
     * Removes every record matching [predicate], re-indexes and saves the affected lists. Returns the
     * removed records. Only removes rows; the caller deletes their files.
     */
    @Synchronized
    override fun removeWhere(predicate: (DbRecord) -> Boolean): List<DbRecord> {
        val removed = synchronized(records) {
            val matching = records.filter(predicate)
            records.removeAll(matching)
            matching
        }
        removed.forEach { savedIn.remove(it.id) }
        indexMap.clear()
        for ((i, r) in records.withIndex()) {
            indexMap[r.id] = i
        }
        // Only the lists that lost rows: the finished one can be large and rarely needs rewriting.
        if (removed.any { it.status.isStopped }) savePausedRecords()
        if (removed.any { it.status == RecordStatus.FINISHED }) saveFinishedRecords()
        if (removed.any { !it.status.isStopped && it.status != RecordStatus.FINISHED }) saveActiveRecords()
        return removed
    }
}

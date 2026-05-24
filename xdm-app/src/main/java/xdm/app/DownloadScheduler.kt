package xdm.app

import xdm.core.util.AtomicIO
import xdm.core.util.Logger
import java.util.Calendar
import java.util.Collections

enum class ScheduleType { ONE_TIME, WEEKLY }

data class ScheduleEntry(
    val downloadId: Long,
    val scheduleType: ScheduleType,
    val hour: Int,
    val minute: Int,
    val daysOfWeek: Set<Int>, // Calendar constants (MONDAY–SUNDAY) for WEEKLY; empty for ONE_TIME
    val epochMillis: Long,    // target epoch millis for ONE_TIME; -1 for WEEKLY
)

class DownloadScheduler(private val appDB: AppDB, private val configDir: String) {

    private val scheduleFileName = "schedule.dat"
    private val entries = Collections.synchronizedSet(mutableSetOf<ScheduleEntry>())

    @Volatile
    private var running = false
    private var thread: Thread? = null

    init {
        loadEntries()
    }

    fun start() {
        Logger.info("Starting scheduler")
        running = true
        thread = Thread {
            while (running) {
                val now = System.currentTimeMillis()
                val msUntilNextMinute = 60_000 - (now % 60_000)
                try {
                    Thread.sleep(msUntilNextMinute)
                } catch (e: InterruptedException) {
                    break
                }
                if (running) tick()
            }
        }.also {
            it.isDaemon = true
            it.name = "DownloadScheduler"
            it.start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }

    @Synchronized
    fun contains(id: Long): Boolean {
        return entries.any { it.downloadId == id }
    }

    @Synchronized
    fun addEntry(entry: ScheduleEntry) {
        entries.add(entry)
        saveEntries()
    }

    @Synchronized
    fun removeEntry(downloadId: Long) {
        entries.removeIf { it.downloadId == downloadId }
        saveEntries()
    }

    fun getEntryFor(downloadId: Long): ScheduleEntry? =
        entries.find { it.downloadId == downloadId }

    private fun tick() {
        Logger.info("Scheduler tick")
        val cal = Calendar.getInstance()
        val nowHour = cal.get(Calendar.HOUR_OF_DAY)
        val nowMinute = cal.get(Calendar.MINUTE)
        val nowDay = cal.get(Calendar.DAY_OF_WEEK)
        val nowMillis = System.currentTimeMillis()
        val nowMinuteBucket = nowMillis / 60_000

        val toRemove = mutableListOf<ScheduleEntry>()

        synchronized(entries) {
            for (entry in entries) {
                val matches = when (entry.scheduleType) {
                    ScheduleType.ONE_TIME -> entry.epochMillis / 60_000 == nowMinuteBucket
                    ScheduleType.WEEKLY -> nowHour == entry.hour && nowMinute == entry.minute && nowDay in entry.daysOfWeek
                }

                if (!matches) continue

                val record = appDB.getById(entry.downloadId)
                if (record != null &&
                    (record.status == RecordStatus.DOWNLOADING
                            || record.status == RecordStatus.ASSEMBLING
                            || record.status == RecordStatus.READY)
                ) {
                    Logger.info("Scheduler: download ${entry.downloadId} already running, skipping")
                    continue
                }

                triggerScheduledDownload(entry.downloadId)

                if (entry.scheduleType == ScheduleType.ONE_TIME) {
                    toRemove.add(entry)
                }
            }
            entries.removeAll(toRemove)
        }

        if (toRemove.isNotEmpty()) saveEntries()
    }

    private fun triggerScheduledDownload(id: Long) {
        // TODO: implement actual download trigger
        Logger.info("Scheduler: triggered download id=$id")
    }

    private fun saveEntries() {
        AtomicIO.writeTransacted(scheduleFileName, configDir) { out ->
            synchronized(entries) {
                out.writeInt(entries.size)
                for (e in entries) {
                    out.writeLong(e.downloadId)
                    out.writeUTF(e.scheduleType.name)
                    out.writeInt(e.hour)
                    out.writeInt(e.minute)
                    out.writeInt(e.daysOfWeek.size)
                    for (day in e.daysOfWeek) out.writeInt(day)
                    out.writeLong(e.epochMillis)
                }
            }
        }
    }

    private fun loadEntries() {
        AtomicIO.readTransacted(scheduleFileName, configDir) { inp ->
            val count = inp.readInt()
            repeat(count) {
                val downloadId = inp.readLong()
                val scheduleType = ScheduleType.valueOf(inp.readUTF())
                val hour = inp.readInt()
                val minute = inp.readInt()
                val dayCount = inp.readInt()
                val daysOfWeek = (0 until dayCount).map { inp.readInt() }.toSet()
                val epochMillis = inp.readLong()
                entries.add(
                    ScheduleEntry(
                        downloadId = downloadId,
                        scheduleType = scheduleType,
                        hour = hour,
                        minute = minute,
                        daysOfWeek = daysOfWeek,
                        epochMillis = epochMillis,
                    )
                )
            }
        }
    }
}

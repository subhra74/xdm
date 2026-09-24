# Scheduler: start and stop times

The scheduler lets a download be started at a chosen time and, optionally, **stopped again** at a
later one. A start calls the same `resumeDownload` path the Resume button uses, and a stop is a pause, not a
cancel: it calls `stopDownload`, so the download keeps its partial data and can be resumed by hand
or by the next scheduled start.

Three files own the feature:

| File | Role |
| --- | --- |
| `xdm-app/.../ui/screens/ScheduleWindow.kt` | The dialog |
| `xdm-app/.../DownloadScheduler.kt` | `ScheduleEntry`, the tick loop, `schedule.dat` persistence |
| `xdm-app/src/main/resources/lang/en.txt` | `MSG_SHD_*` strings |

## The model

```kotlin
data class ScheduleEntry(
    val downloadId: Long,
    val scheduleType: ScheduleType,   // ONE_TIME | WEEKLY
    val hour: Int,
    val minute: Int,
    val daysOfWeek: Set<Int>,         // Calendar constants; WEEKLY only
    val epochMillis: Long,            // start instant; ONE_TIME only, -1 for WEEKLY
    val hasStopTime: Boolean = false,
    val stopHour: Int = 0,            // WEEKLY stop time
    val stopMinute: Int = 0,          // WEEKLY stop time
    val stopEpochMillis: Long = -1L,  // ONE_TIME stop instant
)
```

`hasStopTime` is the single switch; when it is false the stop fields are ignored and no stop event
is ever evaluated. The two schedule types store the stop differently, mirroring how each stores its
start:

- **ONE_TIME** — a full instant (`stopEpochMillis`), built in the dialog from a separate stop *date*
  and stop *time*. A full date is what allows a one-time download to run across midnight, or across
  several days.
- **WEEKLY** — a wall-clock `stopHour`/`stopMinute`, interpreted against the entry's own
  `daysOfWeek`.

## How a tick decides

`DownloadScheduler.tick()` runs once per wall-clock minute (the thread sleeps to the next minute
boundary). Each entry is evaluated for two independent events:

```
startMatches  ONE_TIME: start instant falls in this minute bucket
              WEEKLY:   hour+minute match and today is in daysOfWeek

stopMatches   hasStopTime, and
              ONE_TIME: stop instant falls in this minute bucket
              WEEKLY:   stop hour+minute match and stopDayMatches()
```

Three rules follow from the two events being separate:

**A start goes through `resumeDownload`.** It queues the download and pumps the queue, and guards
internally against one that is already active or already queued, so a repeated tick is harmless.

**A stop ignores the "already running" guard.** The start path skips an entry whose record is
`DOWNLOADING`, `ASSEMBLING`, `PUBLISHING` or `READY` — those are exactly the states a stop exists
for, so the stop is evaluated and fired before that guard is reached.

**A one-time entry survives its own start.** Entries used to be deleted the moment they fired. With
a stop pending that would throw the stop away, so removal is now held back until the stop has fired
or its instant has passed:

```kotlin
val stopPending = entry.hasStopTime && !stopMatches && nowMillis <= entry.stopEpochMillis
if ((startMatches || stopMatches) && !stopPending) toRemove.add(entry)
```

**A weekly window may cross midnight.** `stopDayMatches()` handles start 23:00 / stop 02:00: when
the stop time is at or before the start time, the window runs into the next day, so the day that
must appear in `daysOfWeek` is *yesterday*, not today.

```kotlin
val wrapsMidnight = entry.stopHour * 60 + entry.stopMinute <= entry.hour * 60 + entry.minute
if (!wrapsMidnight) return nowDay in entry.daysOfWeek
val previousDay = if (nowDay == Calendar.SUNDAY) Calendar.SATURDAY else nowDay - 1
return previousDay in entry.daysOfWeek
```

## The dialog

Both cards gained a checkbox (`MSG_SHD_ENABLE_STOP`) that enables its own stop controls —
`syncStopControls()` greys out the spinners and their labels when the box is clear, and is called
once after `populateExistingEntry()` so a reopened dialog starts in the right state.

- **One-time card**: `Stop date` (a second `yyyy-MM-dd` date spinner) plus `Stop time` hour/minute.
- **Weekly card**: `Stop time` hour/minute only; the days come from the start row.

Validation in `onSchedule()`:

- One-time — the stop instant must be strictly after the start instant, otherwise `MSG_SHD_MESSAGE2`.
- Weekly — an *earlier* stop is legal (it means the midnight wrap above); only an identical
  start and stop time is rejected.

The dialog is 500×460 to fit the taller one-time card.

## Persistence

`schedule.dat` is the hand-written `DataInput`/`DataOutput` format in `saveEntries`/`loadEntries`.
The four new fields are appended per record, in this order, after `epochMillis`:

```
writeBoolean(hasStopTime)
writeInt(stopHour)
writeInt(stopMinute)
writeLong(stopEpochMillis)
```

The format is positional and carries no version header, so **the read and write halves must be kept
in lockstep**, exactly as with `TaskInfoDB`. There is deliberately no migration: a `schedule.dat`
written before this change fails to parse and its entries are dropped. `loadEntries` now logs that
failure instead of swallowing it.

## Known limitations

- **A queued start waits for a free slot.** `resumeDownload` adds the download to the queue and
  pumps it; if `maxParallelDownloads` is already reached the download starts when a slot frees up,
  not at the scheduled minute.
- **Missed minutes are missed events.** Both start and stop match an exact minute bucket, so a
  machine asleep or an app closed over that minute skips the event entirely. Making this robust
  means moving from edge events to a window the tick re-asserts each minute (inside the window →
  should be running; outside → should be stopped), which is a larger semantic change.

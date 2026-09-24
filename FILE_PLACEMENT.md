# File placement model

Where a download's bytes live from the first request to the finished file on disk, and the
rules that govern moving them. This supersedes the placement logic each downloader grew
independently.

Status: **design agreed, not yet implemented.** Section 8 lists the work.

---

## 1. The invariant

> Working data — the HTTP temp file and streaming segments — lives in the temp folder and
> never depends on the destination. The streaming assembled output is the one artifact
> written into the destination, and its path is **recorded when chosen, never recomputed**.
> A file under its real name in a destination folder is always complete.

Three consequences, and they are the point of the design:

- **Renaming or re-categorizing a running download costs nothing.** No bytes move, no path
  is recomputed mid-flight, nothing is orphaned. The user may change the name, switch the
  category, or repoint the destination at any moment, including while the download is
  active or paused.
- **Publish is atomic.** The final name appears in one instant, via a rename. A crash at
  any point leaves either no file under that name or a complete one — never a truncated
  file that looks finished.
- **Streaming never pays a copy.** Its output is muxed straight into the destination
  folder, so publishing it is a same-folder rename regardless of size.

The asymmetry between HTTP and streaming is deliberate. HTTP's bytes arrive incrementally
over a long window in which the user may change anything, so its working file must be
destination-independent. Streaming's output is produced in one short pass *after* everything
is decided. The orphaned-partial bug in today's code (§2) comes from recomputing that path,
not from writing into the destination — fix the recomputation and the exception is safe.

---

## 2. Current model (as-is)

|  | HTTP | HLS / DASH |
|---|---|---|
| Working data | one sparse file `<uid>.tmp` in the **task's base download folder** ([HttpDownloader.kt:62](xdm-core/src/main/java/xdm/core/downloaders/web/http/HttpDownloader.kt:62), written at [HttpChunkRetriever.kt:436](xdm-core/src/main/java/xdm/core/downloaders/web/http/HttpChunkRetriever.kt:436)) | segment files under `config.tempFolder/<id>/` ([DownloadManager.kt:616](xdm-app/src/main/java/xdm/app/DownloadManager.kt:616)) |
| Assembled output | none — the temp file *is* the output | muxed into the **destination folder** as `.<id>.xdm-part<ext>` ([DownloadManager.kt:321](xdm-app/src/main/java/xdm/app/DownloadManager.kt:321)) |
| Publish | `FileUtils.moveFile` into the category/target folder ([DownloadManager.kt:250](xdm-app/src/main/java/xdm/app/DownloadManager.kt:250)) | same-folder rename, via the same path |
| Resume state | `~/.xdm-app/task-<id>.info`, `~/.xdm-app/<id>.state` | same |

HTTP already satisfies the naming half of the invariant: the temp file is `<uniqueId>.tmp`
and the real name and folder are resolved only at commit. It does not satisfy the location
half — the working file sits in the user's download folder.

### Defects this design closes

| | |
|---|---|
| **HTTP temp folder is never created** | `getTempDir` returns the task's download folder ([DownloadManager.kt:238](xdm-app/src/main/java/xdm/app/DownloadManager.kt:238)) and `RandomAccessFile(..., "rw")` opens into it with no `mkdirs`. If that folder does not exist — a fresh install with no `~/Downloads`, or a folder typed into settings — the first chunk write fails with `FileNotFoundException`. Streaming is unaffected ([StreamingDownloaderTask.kt:68](xdm-core/src/main/java/xdm/core/downloaders/web/streaming/downloader/StreamingDownloaderTask.kt:68) creates its temp dir). |
| **Streaming partial is orphaned on destination change** | `deletePartialOutput()` ([:105](xdm-core/src/main/java/xdm/core/downloaders/web/streaming/downloader/StreamingDownloaderTask.kt:105)) and the retry-commit check ([:213](xdm-core/src/main/java/xdm/core/downloaders/web/streaming/downloader/StreamingDownloaderTask.kt:213)) both recompute the output folder from live task metadata. Mux completes → commit fails or the app is killed → the user re-categorizes or repoints the folder → resume recomputes a different path, re-muxes there, and leaves the original part file behind, invisible to delete. |
| **Free space is checked once, late, on the wrong volume** | [FileUtils.kt:162](xdm-core/src/main/java/xdm/core/util/FileUtils.kt:162) checks the destination at commit. Nothing checks the volume the bytes actually accumulate on, at the time they start accumulating. |
| **Publish is not durable** | The copy fallback never fsyncs before the final rename, so a power loss can publish a truncated file under the real name. |
| **Publish reports no progress and cannot be cancelled** | A large cross-volume copy presents as a frozen UI at 100%. |
| **Part file is not hidden on Windows** | A leading dot hides nothing there, so `.{id}.xdm-part.mp4` is a normal visible file in the user's folder during muxing. |
| **`~/.xdm-app/tmp` is dead** | [AppMain.kt:50](xdm-app/src/main/java/xdm/app/AppMain.kt:50) creates it and passes it to `AppContext.init`, which ignores the parameter ([AppContext.kt:32](xdm-app/src/main/java/xdm/app/AppContext.kt:32)). The temp folder actually used is `config.tempFolder`, default `~/.temp` ([AppConfig.kt:92](xdm-app/src/main/java/xdm/app/AppConfig.kt:92)). |

---

## 3. Target model

### Layout

```
~/.xdm-app/                          config and state
  task-<id>.info                     immutable task parameters
  <id>.state                         resume state, incl. the recorded mux output path
  <id>.keys                          HLS keys
  tmp/                               config.tempFolder — user-settable, default this path
    <id>/                            one folder per download, created at start
      data.tmp                       HTTP: the sparse file being filled
      seg-*                          streaming: downloaded segments
<destination>/                       user-selected folder, or the category's folder
  .<id>.xdm-part<ext>                streaming only: the muxed output, hidden
  <final name>                       appears only on successful publish, always complete
```

`config.tempFolder` defaults to `~/.xdm-app/tmp`, reusing the directory `AppMain` already
creates. Deliberately **not** `java.io.tmpdir`: on most Linux distributions `/tmp` is tmpfs
and RAM-backed, so a large download would exhaust memory, and OS temp cleaners
(`systemd-tmpfiles`, macOS `/var/folders` reaping) delete stale files — a download left
paused for a week could be purged from under us.

### HTTP lifecycle

1. **Start.** Create `<config.tempFolder>/<id>/`. Once the content length is known, check
   free space on the temp volume and **warn if short** (§5.3). Bytes accumulate only there.
2. **Running.** Name, category and destination may change freely; only `task-<id>.info` is
   rewritten. Nothing on disk moves.
3. **Publish.** Resolve the destination *now* — the category's folder when auto-categorized,
   otherwise the task's folder. Create it, pick a unique name, move (§5). This may cross
   volumes.
4. **Clean up.** Delete `<config.tempFolder>/<id>/`.

### Streaming lifecycle

1. **Start.** Create `<config.tempFolder>/<id>/`; same space warning. Segments accumulate
   there. The destination is not touched.
2. **Running.** As HTTP — everything is metadata.
3. **Mux begins.** Resolve the destination **once**. Create it. Form
   `<destination>/.<id>.xdm-part<ext>`, mark it hidden on Windows, and **record the absolute
   path in `<id>.state`**. From this moment the part file's location is a recorded fact.
4. **Mux.** Read segments from temp, write the part file directly into the destination —
   one pass, no intermediate copy.
5. **Publish.** Read the recorded path, resolve the final name in that folder, rename.
   Same directory, therefore same volume, therefore atomic and free.
6. **Clean up.** Delete `<config.tempFolder>/<id>/`.

**Every later operation uses the recorded path** — commit, the retry-after-failed-commit
check, and delete. None of them recompute it. If the user repoints the destination after
muxing, publish moves from the recorded path to the new destination and `moveFile` handles
the cross-volume case; without the record, that case silently re-muxes and orphans.

### Why the part file, and not muxing straight to the final name

Muxing directly to `<destination>/<final name>` would save exactly one `rename()` syscall —
the part file is already in the destination folder, so no bytes move either way. It costs:

| | Part file + rename | Mux as commit |
|---|---|---|
| Crash during mux | hidden, id-named partial; obviously internal | `clip.mp4` truncated, indistinguishable from success |
| Crash after mux, before publish | complete part file; retry only the rename | must re-mux or persist a "mux done" flag |
| Name resolution window | milliseconds, immediately before the rename | the whole mux duration; two concurrent downloads can pick the same name |
| Delete mid-assembly | targets an obviously-internal file | targets a file with the user's real name |

Row one is the decision. Handing the user a file that looks finished and isn't is the worst
failure mode a download manager has.

---

## 4. What this fixes

- Partial downloads no longer appear in user folders for HTTP, and cloud-sync folders no
  longer see them.
- The destination folder need not exist until publish (HTTP) or mux (streaming).
- The HTTP first-run failure disappears: `config.tempFolder` is the one folder that must
  exist early, and it is created at startup.
- Cleanup and delete are one `deleteFolder` per download.
- The streaming orphan bug is structurally impossible.
- Streaming performance is unchanged — still one write pass into the destination.

---

## 5. Publish

### 5.1 What already exists

[`FileUtils.moveFile`](xdm-core/src/main/java/xdm/core/util/FileUtils.kt:142) is already the
intended algorithm:

1. `Files.move(src, dst, ATOMIC_MOVE)` — a metadata-only rename when source and destination
   share a volume, including same-volume NTFS.
2. On `AtomicMoveNotSupportedException`, check free space on the destination, copy to a
   scratch file, atomically rename it into place, delete the source.

Both platforms surface the cross-volume case as that same exception — Windows via
`MoveFileEx` failing `ERROR_NOT_SAME_DEVICE`, Unix via `rename(2)` returning `EXDEV` — so no
platform-specific volume detection is needed on the publish path. Nothing new to build here;
§5.2–§5.5 are the gaps.

Streaming never reaches step 2: its source and destination are the same directory.

### 5.2 Durability

`Files.copy` does not flush to stable storage. The copy path must fsync the scratch file
before the final rename — otherwise a power loss publishes a truncated file under the real
name, which is worse than failing because the user believes the download succeeded.

Open the scratch file, copy, `FileChannel.force(true)`, close, then rename.

### 5.3 Free space

Two checks:

- **At download start**, against the temp volume, once the content length is known. This is
  the one that matters under this model, because the temp volume is where the bytes land.
  It **warns and proceeds** rather than blocking — server-reported lengths are sometimes
  wrong, and refusing a download over a bad `Content-Length` is worse than letting it try.
- **At publish**, against the destination volume, on the copy path only — a same-volume
  rename consumes no additional space. This is the existing check and is already correctly
  placed inside the fallback.

### 5.4 Publish is a cancellable, retryable phase

A cross-volume publish moves real bytes and can take minutes. It gets a status of its own,
distinct from `ASSEMBLING`, so the UI can say what is happening and offer a working cancel.

- **Progress** is reported per byte copied, as during download.
- **Cancel** deletes the scratch file and leaves the source intact in temp. The download
  becomes `PAUSED`, not failed.
- **Resume from that state re-publishes only** — it must not re-download. The HTTP context
  already carries a `completed` flag; resume has to honour it and skip straight to publish.
  For streaming the recorded mux path plays the same role.

This also gives the user a way out of a failed publish: repoint the destination to a folder
with room, resume, and the finished bytes are still in temp.

Both UI surfaces must handle the new status:

- **Download list row** ([MainListViewRow.kt:341](xdm-app/src/main/java/xdm/app/ui/components/MainListViewRow.kt:341)) — the pause/cancel button must be visible during publish, and resume hidden, matching how `ASSEMBLING` is treated today; the status line needs its own text rather than falling through to a generic state.
- **Progress window** — a labelled publish phase with byte progress and a cancel that maps to the same action as the row button.

Status plumbing also touches the active-list partition ([DownloadsDB.kt:80](xdm-app/src/main/java/xdm/app/DownloadsDB.kt:80)), the scheduler's in-flight test ([DownloadScheduler.kt:101](xdm-app/src/main/java/xdm/app/DownloadScheduler.kt:101)) and `clearInactive` ([DownloadManager.kt:486](xdm-app/src/main/java/xdm/app/DownloadManager.kt:486)), each of which currently enumerates `ASSEMBLING` explicitly.

### 5.5 Name collisions

`getUniqueFileName` picks the final name, then the copy path derives its scratch name from
it. Two concurrent downloads resolving to the same destination name can both pass the
uniqueness check before either creates a file, then collide on the scratch name. Derive it
from the download id instead — `<dst>.<id>.part` — which is unique by construction.

### 5.6 Hidden part file on Windows

The streaming part file relies on a leading dot, which hides nothing on Windows. Set
`FILE_ATTRIBUTE_HIDDEN` (via `Files.setAttribute(path, "dos:hidden", true)`) when creating
it, and ignore failures on filesystems that do not support the attribute.

---

## 6. Cross-volume warning

Advisory only. §5 is what makes a cross-volume publish *safe*; the warning exists so users
can avoid paying for it at all.

**Where.** Inline text where a folder is chosen, never a blocking dialog:
- General settings → Folders, when the temp folder and the default download folder are on
  different volumes.
- The category edit dialog, when the category's folder is on a different volume from the
  temp folder.

**Wording** states the consequence, not the mechanism: finished downloads will be copied
rather than moved, and putting the temp folder on the same drive avoids it.

**Detection.** Compare `Files.getFileStore()` for the two paths. Two caveats:

- `getFileStore` throws if the path does not exist, and category folders are created lazily,
  so most will not exist when we want to compare. Walk up to the nearest existing ancestor.
- Network shares and UNC paths can report stores that do not compare meaningfully.

On any exception or ambiguity the check **fails silent** — shows nothing. A wrong warning
about the user's own disk layout is worse than no warning.

Because the default temp folder now lives under `~/.xdm-app`, the default configuration puts
in-flight bytes on the home volume. For anyone downloading to a second drive that is exactly
the case this warning is about, and it will fire out of the box. That is intended: it is
visible, explicable, and one setting away from being fixed.

---

## 7. Trade-offs

**Gained.** Renaming and re-categorizing are free at any time, including mid-download. No
orphaned partials. No HTTP partials in user folders. Destination folders need not exist
early. The HTTP first-run failure disappears. Publish is atomic and durable. Cleanup is one
operation. Streaming performance is unchanged.

**Paid.**

- *HTTP publish may copy instead of rename.* Today HTTP's temp sits in the download folder,
  so publish is usually free; under this model, users whose temp and destination are on
  different drives pay a full byte copy at the end of every HTTP download. Mitigated by §6,
  made safe and interruptible by §5. Note the current model's "free rename" is already
  weaker than it looks: category folders are independent absolute paths, so a category on
  another drive already forces a copy today.
- *Streaming partials remain visible in the destination folder* while muxing — hidden, but
  present, and cloud-sync will see them. This is the price of not copying.
- *Peak temp usage is concentrated.* All in-flight bytes live under one folder; ten queued
  5 GB downloads need 50 GB there, where today HTTP temps are spread across destinations.
  The start-time warning (§5.3) turns this from a late failure into an early, explicable one.
- *`config.tempFolder` becomes load-bearing.* Pointed somewhere small or removable, it
  affects every download rather than just streaming. Validate it when set; create it at
  startup.

**Rejected alternatives.**

- *Mux straight to the final name ("mux as commit").* Saves one syscall, costs atomic
  publish. Table in §3.
- *Mux into temp, then publish* (fully uniform). Structurally tidy, but makes every
  cross-volume user pay an extra full read+write of the muxed output on every video, to fix
  a bug whose actual cause is recomputation.
- *Hidden part file in the destination for HTTP too.* Publish would always be a rename, but
  HTTP partials would sit in user folders for the whole download, the destination would have
  to exist up front, and changing it mid-download would require moving the partial.
- *Hybrid — temp when same-volume, part file otherwise.* The guarantee is illusory: the
  decision is made at start but the destination can change at any time, so it can end up
  copying anyway, or holding a partial in the wrong folder. Two code paths for an unreliable
  optimization.

---

## 8. Implementation

**xdm-app**

- `config.tempFolder` defaults to `~/.xdm-app/tmp`; validate it when set in settings.
- `AppMain` creates it at startup; `AppContext.init`'s `tempDir` parameter becomes
  meaningful instead of ignored.
- `DownloadManager.getTempDir` returns `<config.tempFolder>/<id>`, creating it.
- `DownloadManager.streamingOutputPath` is consulted **once** per download, at mux start.
- `RecordStatus` gains the publish state; update the active-list partition, the scheduler's
  in-flight test, `clearInactive`, the row buttons and status text, and the progress window
  (§5.4). New `STAT_*` language key.
- Settings and category dialog: the §6 warning.
- `deleteMetadata`/`purgeFiles` purge `<config.tempFolder>/<id>/` — CLAUDE.md requires every
  new per-download file to be registered there.

**xdm-core**

- Record the mux output path in `<id>.state` and read it in `outputFile()`,
  `deletePartialOutput()` and the retry-commit path. Keeping it in `<id>.state` rather than
  `*DownloadTaskInfo` avoids touching the `TaskInfoDB` binary format.
- `MoveOps`: fsync before the final rename (§5.2); id-derived scratch name (§5.5).
- `FileUtils.moveFile`: progress callback and cancellation on the copy path (§5.4).
- Start-time free-space warning against the temp volume (§5.3).
- `FILE_ATTRIBUTE_HIDDEN` on the streaming part file (§5.6).
- Resume honours the completed-but-unpublished state and skips to publish (§5.4).

**Migration.** None needed for in-flight downloads. `loadState` restores the persisted
`HttpTaskContext.tempFolder`, and `getTempDir` is consulted only in the not-yet-initialized
branch ([HttpDownloader.kt:276](xdm-core/src/main/java/xdm/core/downloaders/web/http/HttpDownloader.kt:276)),
so a download already running keeps its original temp location and resumes normally. Only
downloads started after the change use the new layout. Streaming partials created before the
change have no recorded path; treat a missing record as "recompute once, then record".

---

## 9. Notes

- `overwriteExistingFiles` and `autoRenameOnConflict` are both surfaced in General settings
  but never read anywhere in the download path — `renameFile` unconditionally calls
  `getUniqueFileName`. Two settings that do nothing. Out of scope here, but they belong to
  this area and should either be wired up or removed.
- The HTTP working file keeps its `<uniqueId>.tmp` name inside the per-download folder. The
  unique prefix is now redundant, since the folder is already unique, but renaming it to
  `data.tmp` is cosmetic and can be skipped to keep the diff small.

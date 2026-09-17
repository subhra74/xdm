# XDM codebase review: incomplete features and known issues

Review date: 2026-09-17 · Branch: `latest` (includes uncommitted working-tree changes)

This review covers `xdm-app`, `xdm-core` (download engine, persistence, HTTP client) and the
browser extensions. The transmuxer (`media/muxer/transmux/**`) was only skimmed because it has its
own end-to-end tests. Line numbers match the files as they were when this review was written.

Severity legend:
- **P0**: security problem, data loss, or a hang or crash that a normal user will hit
- **P1**: a real bug that breaks a common flow
- **P2**: a correctness problem in an edge case, or a resource leak
- **P3**: code hygiene or a latent risk

---

## Summary table

| # | Sev | Area | Issue |
|---|-----|------|-------|
| S1 | P0 | Integration | ~~Any website could make the app add downloads~~ **Fixed:** loopback + Origin allowlist + POST-only for state-changing paths |
| S2 | P0 | Network | ~~TLS checks turned off for every download~~ **Fixed:** verified by default; opt-out setting in Advanced → Security |
| B1 | P0 | HTTP engine | Read→write lock upgrade deadlocks the chunk thread on resume |
| B2 | P0 | HTTP engine | An HTTP 429 response kills the chunk thread and the download hangs forever |
| B3 | P1 | Persistence | Queued and "download later" tasks lose cookies, headers and origin |
| B4 | P1 | HLS | Encrypted HLS always fails after a pause or restart (AES keys are only held in memory) |
| B5 | P1 | Queue | HLS/DASH ignore `maxParallelDownloads`, and duplicate callbacks start too many queued downloads |
| B6 | P1 | Video | Downloading the same detected video twice reuses the same task id |
| B7 | P1 | Commit | `File.renameTo` fails across drives, so HLS/DASH to another volume fails with "disk space error" |
| B8 | P1 | Integration | Malformed HTTP response headers from the local server |
| B9 | P1 | Persistence | `AtomicIO` reads a half-written `.bak1` in preference to the good file |
| B10 | P2 | Resources | Thread pools and OkHttp clients leak for every streaming download and every paused HTTP download |
| B11 | P2 | HTTP engine | `readTimeout = 0`, so a stalled connection blocks forever and Pause cannot unblock it |
| B12 | P2 | Cleanup | Deleting a download never removes `task-<id>.info` / `<id>.state`; `AppDB.clear()` is also broken |
| B13 | P2 | UI | "Clear" wipes the DB while downloads are still running |
| B14 | P2 | App lifecycle | Exit does not pause downloads or save state; a failed server bind leaves a headless JVM running |
| B15 | P2 | Threading | Several UI calls run off the EDT; `queue` and `toDelete` are accessed without synchronization |
| B16 | P2 | HTTP client | `contentType` loses the MIME subtype; `Cookie` values are joined with `;` |
| B17 | P2 | Privacy | Cookies and auth headers are written to the log file |
| B18 | P2 | Integration | The local server has no body size limit, no socket timeout, and makes a thread per connection |
| B19 | P2 | Video | `VideoHelper` leaks manifest temp files, keeps growing its sets, captures a stale proxy, and can NPE |
| B20 | P2 | Status | Failures are shown as "Paused"; `RecordStatus.ERROR` is never used |
| B21 | P3 | Misc | Smaller issues (see list) |
| F1–F14 | — | Features | Incomplete or stubbed features (see section 2) |

---

## 1. Bugs and risks

### S1 (P0): The local integration server accepted commands from any web page (FIXED)
**Where:** `xdm-app/.../integration/HttpServer.kt`, `HttpParser.kt`, `RequestContext.kt`, `BrowserIntegration.kt`

**Status:** Fixed in the working tree and compiles. Still to do: run the curl checks and the
browser checks below.

#### The problem
The server accepted every request reaching `127.0.0.1:8597` and routed on the path alone, ignoring
the HTTP method. Because a web page runs in the browser on the same machine, its requests come from
the same loopback address as the extension's. A page could:
- send a CORS-simple POST, e.g. `fetch("http://127.0.0.1:8597/download", {method:"POST", mode:"no-cors", body})`,
  exactly as the extension does, to queue downloads (they start silently if
  `startDownloadAutomatically` is on), pollute `/media`, or open `/vid` dialogs;
- send a plain GET with no `Origin`, e.g. `<img src="http://127.0.0.1:8597/clear">`, to clear the list.

#### Constraints
- Local tools (for example curl or scripts) must keep working, so there is no token or `Host`
  allowlist, and requests with no `Origin` are allowed.
- An IP check alone doesn't block web pages, for the reason above. It is kept only as a safety net
  in case the bind address changes.

#### Where the real traffic comes from (verified in the extension code)
Every request goes through `Connector` (`chrome-extension/connector.js`,
`firefox-extension/app/connector.js`) in the extension's background context: the service worker on
Chrome MV3, the background page on Firefox MV2. Neither manifest declares `content_scripts` or
`web_accessible_resources`, and the popup only talks to the background through
`chrome.runtime.sendMessage`.

| Call | Method |
|------|--------|
| `/sync` (poll every ~5 s) | GET |
| `/download`, `/media`, `/vid`, `/clear`, `/tab-update` | POST (`body: JSON.stringify(...)`, `text/plain`) |

#### Observed headers (Chrome 152 and Firefox 152 on macOS)
| Browser | Request | `Origin` | `Sec-Fetch-Site` | `Host` |
|---------|---------|----------|------------------|--------|
| Chrome | GET `/sync` | *(none)* | `none` | `127.0.0.1:8597` |
| Chrome | POST `/download` | `chrome-extension://jcjlmfolckgddgcdheneibfilmcjekkj` | `none` | `127.0.0.1:8597` |
| Firefox | POST `/download` | `moz-extension://1e6f7e9d-d7dd-48d2-89a0-38a0569c1565` | `same-origin` | `127.0.0.1:8597` |

Firefox reports `same-origin`, so `Sec-Fetch-Site` is not a reliable signal and is not used. The
`moz-extension` UUID is random per install, so origins are matched on scheme, not on a fixed id.
Web pages always send their own `Origin` on POST (`http(s)://...`, or `null` from sandboxed frames).

#### What was implemented
1. **Loopback-only connections** (`HttpServer.process`): a non-loopback socket is logged and closed
   before any request is read.
2. **The request method is now parsed** (`HttpParser.parseRequestStatusLine` returns
   `(method, path)`; `RequestContext.requestMethod`). A case-insensitive
   `RequestContext.getRequestHeader(name)` was added.
3. **Checks run before dispatch** (`BrowserIntegration.rejectionStatus`, called from `handleRequest`):
   - `Origin` present and not starting with `chrome-extension://`, `moz-extension://`,
     `extension://` or `safari-web-extension://` → **403 Forbidden**. This includes `Origin: null`.
   - A non-POST to `/download`, `/media`, `/vid`, `/clear` or `/tab-update` → **405 Method Not
     Allowed**. The path is compared without its query string.
   - No `Origin` → allowed (Chrome's `/sync` poll, local tools).
   - Each rejection is logged with its method, path and origin. The request body is not logged.
4. No `Access-Control-Allow-Origin` header is sent, so pages still can't *read* `/sync`, which
   lists detected video titles.
5. The temporary header logging used to collect the data above was removed.

#### Residual risk (accepted)
- Any local process can call the API, and so can another installed extension with host
  permissions. Both already run with the user's privileges, and local access is required.
- A web page can still send a GET to `/sync`, but without CORS headers it can't read the response,
  and `/sync` changes nothing.
- The Origin allowlist doesn't pin extension ids. Chrome's id could be pinned once it is stable
  (store-published, or a `key` in the manifest).

#### Verification
```bash
curl -i -X POST -H "Origin: https://example.com" --data '{}' http://127.0.0.1:8597/download   # 403
curl -i -X POST -H "Origin: null" --data '{}' http://127.0.0.1:8597/download                  # 403
curl -i http://127.0.0.1:8597/clear                                                           # 405
curl -i http://127.0.0.1:8597/sync                                                            # 200 + JSON
```
Then, from both Chrome and Firefox: confirm the extension shows as connected, start a browser
download, click a detected video in the popup, and use Clear.

### S2 (P0): TLS verification was disabled for all downloads (FIXED)
**Where:** `xdm-core/.../network/http/impl/HttpClientImpl.kt`

**Status:** Fixed in the working tree and compiles. The behaviour was checked against live test
hosts (see Verification). The settings UI has not been clicked through yet.

#### The problem
Every `HttpClientImpl` installed a trust-all `X509TrustManager` and `hostnameVerifier { _, _ -> true }`.
Any network attacker could intercept or replace downloads, including executables, and read the
cookies and auth headers forwarded from the browser.

#### What was implemented
1. **Secure by default** (`HttpClientImpl`): the client uses OkHttp's platform trust store and
   hostname verification. A new constructor parameter, `ignoreCertErrors: Boolean = false`, installs
   the trust-all manager and hostname verifier only when true, and logs that it did. The
   `SSLContext` protocol changed from `"SSL"` to `"TLS"`.
2. **User setting** (`AppConfig.ignoreCertErrors`, default `false`): persisted as a new trailing
   field in `xdm-app.config`. Older config files hit `EOFException` there and keep the secure default.
3. **Settings UI** (`AdvancedConfigPanel`): a new **Security** card in Advanced settings with an
   "Ignore TLS certificate errors (insecure)" checkbox and a hint. Ticking it shows a warning
   dialog, and choosing No unticks it. Strings are in `lang/en.txt` (`SETTINGS_SEC_SECURITY`,
   `MSG_IGNORE_CERT_ERRORS*`); other languages fall back to English.
4. **Call sites:** `DownloadManager` builds every client (HTTP, HLS, DASH; start and resume)
   through `newHttpClient()`, which passes the setting. `VideoHelper`'s manifest client is rebuilt
   whenever the proxy or this setting changes, so edits apply without a restart. That also fixes the
   stale-proxy part of B16.

#### Behaviour notes
- The setting is read when a download starts or resumes. A download already running keeps its
  current client, so pause and resume it to apply a change.
- With checks on, a certificate or hostname failure fails the download straight away with
  **`DownloadError.TlsError`** (added as a follow-up). There is no retry loop. The progress window
  shows "Secure connection failed: the server's certificate could not be verified. If you trust
  this server, enable "Ignore TLS certificate errors" in Settings > Advanced." How it works:
  - `xdm-core/.../network/http/TlsErrors.kt`: `isTlsVerificationError(t)` walks the cause chain
    for `SSLHandshakeException`, `SSLPeerUnverifiedException` or `CertificateException`. Other
    `SSLException`s (for example a reset in the middle of a TLS record) still count as retryable
    network errors.
  - HTTP: `HttpChunkRetriever.connect` returns the new `ConnectResult.TlsError`, and the chunk
    fails with `DownloadError.TlsError`.
  - HLS/DASH segments: `StreamingChunkRetriever` marks the piece `Failed` with `TlsError` instead
    of retrying forever.
  - Manifest and AES key fetches: `ManifestUtils.downloadManifestAsFile/Bytes` take an optional
    `onError` callback. `StreamingDownloaderTask` records TLS failures there and reports `TlsError`
    in place of `InvalidResponse` / `InternalError` when setup fails.
  - UI: `ProgressWindow` maps it to `ERR_TLS`. The error label now wraps (HTML) and has a tooltip,
    so long messages aren't cut off in the 400 px window.
  - The failure is not persisted: as with other errors (B20), the record shows as Paused once the
    progress window is closed.
- `UpdateChecker` uses `HttpURLConnection` with JVM defaults and was already verifying certificates.

#### Verification
A temporary JUnit test (since removed) called `HttpClientImpl.getResponse` against live hosts:

| URL | `ignoreCertErrors=false` | `ignoreCertErrors=true` |
|-----|--------------------------|-------------------------|
| `https://example.com/` | HTTP 206 | HTTP 206 |
| `https://self-signed.badssl.com/` | `SSLHandshakeException` | HTTP 206 |
| `https://wrong.host.badssl.com/` | `SSLPeerUnverifiedException` | HTTP 206 |

`TlsError` check (temporary test, since removed): the detector returns true for the two badssl hosts
and false for a refused connection (`ConnectException`). A full `HttpDownloaderTask` against
`https://self-signed.badssl.com/` failed with `TlsError` in about 2.4 s. The existing
`TestHttp*`, `TestHlsE2E` and `TestDashE2E` suites still pass (44 tests). The HLS/DASH TLS paths
were not exercised against a live bad-certificate server.

Still to check by hand: open Settings → Advanced, tick the box, choose No (it should untick), tick
again and choose Yes, save, reopen Settings (it should stay ticked), and download from
`https://self-signed.badssl.com/` with the box on and off.

### B1 (P0): Deadlock when resuming a chunk that is already complete
**Where:** `HttpChunkRetriever.kt:431-443` (`isAlreadyDone`)

`isAlreadyDone()` holds `context.read {}` and calls `controller.onChunkFinished(id)`, which takes
`context.write {}` (`HttpDownloader.kt:189`). `ReentrantReadWriteLock` cannot upgrade a read lock to
a write lock, so the thread deadlocks on itself. Every other chunk thread and `stop()` then block on
the same lock. This happens when a chunk's bytes are all on disk but `Finished` was never persisted,
for example after a pause or crash right after the last write.

**Fix:** Decide what to do inside the read lock and act after releasing it:
```kotlin
private fun isAlreadyDone(): Boolean {
    var finishNow = false
    context.read {
        val chunk = context.chunks[id] ?: return true
        if (chunk.status.get() == ChunkStatus.Finished) return true
        finishNow = context.init.get() && chunk.length.get() > 0 &&
                    chunk.length.get() - chunk.downloaded.get() <= 0
    }
    if (finishNow) {
        context.write { context.chunks[id]?.status?.set(ChunkStatus.Finished) }
        controller.onChunkFinished(id)
    }
    return finishNow
}
```
Also audit the other `read {}` blocks for callbacks that might take the write lock.
`updateBytesDownloaded` → `saveState` is fine today.

### B2 (P0): HTTP 429 kills the chunk thread
**Where:** `HttpChunkRetriever.kt:121-125`

On 429 the code runs `throw IOException("Rate limit hit")` inside the inline `onSuccess` lambda. The
exception escapes `connect()` and `retrieveChunk()`, which have no try/catch, so the thread dies. The
chunk stays `Downloading` forever and is never counted as failed or finished, so the download hangs
at a partial percentage with no error. The computed `retryAfter` is also thrown away.

**Fix:** `return ConnectResult.Retry(retryAfter)`. Wrap the body of `retrieveChunk()` in a
`try/catch (Exception)` that calls `chunkFailed(DownloadError.InternalError)` so an unexpected
exception can never leave a chunk orphaned. Cap `retryAfter` (for example at 60 s) and sleep in
short steps that check `isCancelled()`, so Pause responds quickly.

### B3 (P1): Queued and deferred HTTP downloads lose cookies, headers and origin
**Where:** `TaskInfoDB.kt:25-46, 97-109`; `DownloadManager.kt:466-470, 331-344`

`saveHttpTask` persists only url, fileName, flags, folder, maxPiece and size. `getHttpTask` returns
`cookie = null, headers = null, origin = null, userSelectedDownloadFolder = null`. The following
paths build the task from the DB with no `.state` file yet, so they start **without auth**:
- `processNextQueue` → `startHttpTask(id)`. The default `maxParallelDownloads = 1`, so every
  second concurrent browser download is affected.
- "Download later" (`runNow = false`) → `resumeDownload` → `resumeImmediately`.
- Any download whose `.state` was never written.

The result is 401/403 responses, or an HTML login page saved as the file. `getOriginPage` also
falls back to the file URL because `origin` and `Referer` are gone, so "Refresh link" opens the wrong page.

HLS and DASH have the same gap (`getHlsTask` / `getDashTask`); they resume correctly only because
the `.state` file carries headers.

**Fix:** Persist `cookie`, `headers`, `origin` and `userSelectedDownloadFolder` in all three
`save*Task` / `get*Task` pairs, keeping read and write order in lockstep as CLAUDE.md requires. Add
a leading format version `Int` so old `.info` files can still be read: version 0 means the old
layout, where these fields default to null. Reuse `writeHeaders` / `readHeaders` from
`StateSaver.kt` by moving them into a shared util. Also see B21 about the 64 KB `writeUTF` limit on
large cookies.

### B4 (P1): Encrypted HLS fails after any pause or restart
**Where:** `HlsDownloaderTask.kt:84, 192-237, 249-275`

`keyCache` is an in-memory map filled only in `initDownload()`. After a resume, `context.init` is
true, so `initDownload()` is skipped and `keyCache` is empty. `decryptChunk` then throws
"Key missing" inside `assemble()`. `downloadChunks()` only catches `InterruptedException`, so the
error reaches `download()`, which reports `InternalError`. The user must delete the download and
start it again.

**Fix (either):**
- In `decryptChunk`, fetch missing keys lazily with
  `keyCache.getOrPut(chunk.keyUrl) { downloadManifestBytes(...) ?: throw IOException("key") }`.
  This is simple and handles key rotation.
- Or persist the keys (hex) in `HlsTaskContext` / `.state`. This works even if the key URL
  expires, but it stores key material on disk.

Lazy fetch is the smaller change. Also catch exceptions in `assemble()` and map them to `MuxError`.

### B5 (P1): The parallel-download limit and queue are unreliable
**Where:** `DownloadManager.kt:171, 186, 206, 519-588, 644-668`

1. `startHlsDownload` / `startDashDownload` always start immediately and ignore
   `maxParallelDownloads`.
2. `processNextQueue()` handles non-resume items only for `DownloadType.Http`. A queued HLS or DASH
   item is dequeued and dropped.
3. `processNextQueue()` runs **outside** the `activeSessions.remove(id)?.let {}` guard. Duplicate
   callbacks each pop a queue item: two chunks can fail at the same moment and both see
   `isAllError()`, or pause and fail can race. This starts more downloads than the limit allows.
   The function also never checks `activeSessions.size < max` before starting one.
4. `resumeImmediately` catches an exception from a missing task (`!!` NPE), leaves the record in
   `READY`, and never advances the queue.

**Fix:** Use a single `schedule()` routine:
```kotlin
private fun pumpQueue() = synchronized(queue) {
    while (activeSessions.size < AppContext.config.maxParallelDownloads && queue.isNotEmpty()) {
        val item = queue.removeFirst()
        if (!startOrResume(item.id)) markFailed(item.id)   // handles Http/Hls/Dash uniformly
    }
}
```
Call it only when a session was actually removed (inside the `let`), and route
`startHls/DashDownload` through the same enqueue-or-start logic as `startHttpDownload`.

### B6 (P1): Downloading the same detected video twice creates an id collision
**Where:** `DownloadManager.kt:494-517`; `NewVideoDownloadWindow.kt:217-231`

`addVideoDownload` passes the tracker's stored `HttpDownloadTaskInfo` / `HlsDownloadTaskInfo` object
straight to `start*Download`. Picking the same video from the extension menu a second time uses
**the same `id`**. The second download overwrites `task-<id>.info` and reuses `<id>.state` (so it
resumes or completes the first download). `appDB.addActive` also adds a second record with the same
id, which corrupts `indexMap`. The shared object is mutated too (`fileName`, `tempDir`,
`defaultDownloadFolder`).

**Fix:** Copy the task with a fresh id before starting it:
`source.copy(id = CoreUtils.uniqueId(), fileName = fileName, ...)`. These are data classes, or can
become ones. Never mutate the object stored in the tracker.

### B7 (P1): Committing the output file fails across filesystems
**Where:** `DownloadManager.kt:223-246` (`renameFile`)

`File.renameTo` fails when the source and destination are on different volumes. HLS and DASH are
assembled in `config.tempFolder` (default `~/.temp`), so saving to an external drive or another
partition always fails. The failure is reported as `DiskSpaceError`, which is misleading.

**Fix:**
```kotlin
try { Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE) }
catch (e: AtomicMoveNotSupportedException) { Files.move(src, dst) }  // copy+delete fallback
```
Add a distinct `DownloadError.FileMoveError` (or `OutputWriteError`) instead of reusing
`DiskSpaceError`. For large files, report progress during the copy fallback, or assemble directly
into the destination folder.

### B8 (P1): The integration server writes malformed response headers
**Where:** `xdm-app/.../integration/RequestContext` (`sendResponse`)

```kotlin
val headers = mutableListOf(headerLine, headerContents)   // headerContents is a List<String>
```
`joinToString` prints the list's `toString()`, so the wire output is
`[Content-Type : [application/json], Cache-Control : [...]]` on one line. The header names are also
followed by `" : "`. It currently works only because Chrome is lenient. The response also sends
`HTTP/1.0` together with `Connection: keep-alive`.

**Fix:**
```kotlin
val lines = mutableListOf("HTTP/1.1 $statusCode $statusMessage")
responseHeaders.filterKeys { !it.equals("content-length", true) }
    .forEach { (k, vs) -> vs.forEach { lines += "$k: $it" } }
lines += "Content-Length: ${responseBody?.size ?: 0}"
lines += "Connection: ${if (keepAlive) "keep-alive" else "close"}"
io.write((lines.joinToString(CRLF) + CRLF + CRLF).toByteArray())
```
Also, `HttpParser` looks up `Content-Length` and `Connection` case-sensitively, so a client sending
`content-length` loses its body. Use the new `RequestContext.getRequestHeader` there as well, or
store header keys lowercased.

### B9 (P1): `AtomicIO.readTransacted` can prefer a corrupt file
**Where:** `xdm-core/.../util/AtomicIO.kt:37-49`

Reads prefer `.bak1`, which is the in-progress temp file. If the process dies while writing it, the
next read picks up the truncated `.bak1`, fails, and **does not fall back** to the intact final file.
That loses config, the download list, or resume state. `renameTo` return values are also ignored.

**Fix:** Try the candidates in order and fall back on any exception:
```kotlin
for (f in listOf(finalFile, tmp3, tmp1)) if (f.exists())
    runCatching { DataInputStream(BufferedInputStream(FileInputStream(f))).use(reader) }
        .onSuccess { return Result.success(it) }
return Result.failure(FileNotFoundException(fileName))
```
Ideally also write a magic number, version, and trailing CRC32 so a truncated file can be detected
instead of partially parsed (see the similar problem in `AppConfig.load`, B21). On the write side,
use `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` and check the result.

### B10 (P2): Thread and connection leaks
**Where:** `StreamingDownloaderTask.kt:29, 63-85, 116-140`; `HttpDownloader.kt:165-185, 221, 479-506`; `AppMain.kt:53`

- `Executors.newFixedThreadPool(maxSegments)` creates non-daemon threads and is shut down only in
  `stop()`. On success or failure the threads stay alive forever: 8 threads for every HLS/DASH download.
- After `stop()`'s `shutdownNow()`, segments that were queued but not started never call
  `latch.countDown()`, so the `download()` thread blocks in `latch.await()` forever.
- A new `HttpClientImpl` (with its own dispatcher and connection pool) is created per task.
  `close()` is called only on the success path of a fresh HTTP download. It is never called on
  pause, failure, `finishDownload()` (resume path), or for any streaming download.
- The periodic `System.gc()` thread in `AppMain` is non-daemon.

**Fix:** Add `DownloaderTask.dispose()`, which shuts down the executor and closes the HTTP client,
and call it from `DownloadManager` whenever a session leaves `activeSessions`. Replace
`latch.await()` with `executorService.invokeAll(...)`, or `await` with a timeout in a loop that
checks `stopFlag`. Better still, share **one** `OkHttpClient` app-wide; OkHttp is designed for that.
Make background threads daemon threads.

### B11 (P2): Stalled connections hang, and Pause does not abort sockets
**Where:** `HttpClientImpl.kt:43`; `HttpDownloader.stop()`

With `readTimeout(0)`, a server that stops sending data blocks `inputStream.read` forever. The
retry logic never runs. `stop()` closes the *file handles* but not the HTTP responses, so the chunk
threads stay blocked until the kernel times out the TCP connection.

**Fix:** Set `readTimeout(60, SECONDS)` (make it configurable) so reads time out and go through the
existing Retry path. Track the live `HttpResponse` (or OkHttp `Call`) per chunk and `close()` or
`cancel()` it in `stop()`.

### B12 (P2): Leftover files on delete; `AppDB.clear()` is broken
**Where:** `DownloadManager.kt:382-429`; `DownloadsDB.kt:216-228`

- `deleteRecord` removes only the in-memory row. `task-<id>.info`, `<id>.state` and the `.bak*`
  files are never removed, so `~/.xdm-app` grows without bound.
- `AppDB.clear()` constructs `File(configDir, "<id>.state")` and `File(..., ".bak2")` but never calls
  `.delete()`. It then deletes `configDir/<id>`, which is not where temp data lives (HLS/DASH use
  `config.tempFolder/<id>`, HTTP temp files live in the download folder). Scheduler entries for
  those ids are left behind too.
- `deleteDownload` ignores `ASSEMBLING` (and `ERROR`) records, so nothing happens.
- `AppDB.removeItem` does not save anything for `ASSEMBLING` records.

**Fix:** Add `DownloadManager.purge(id)`: stop the task if it is active, delete the temp data
(via `DownloaderTask.deleteTemp()` or the state's temp path), then `taskInfoDB.deleteRecord(id)`,
delete `<id>.state{,.bak1,.bak2}`, and call `scheduler.removeEntry(id)`. Use it from delete, clear
and delete-completed.

### B13 (P2): "Clear" while downloads are running
**Where:** `MainListView.kt:263-270` → `AppDB.clear()`

Clearing removes records while `activeSessions` keeps downloading. Later callbacks find no record,
and on completion the files are committed but never shown. The temp files of in-flight downloads
are orphaned.

**Fix:** Either stop all active sessions first and wait for `onDownloadPaused` before purging, or
make Clear remove only finished records (the conventional behaviour), with a separate "Delete all"
action.

### B14 (P2): App lifecycle problems
**Where:** `AppToolBar.kt:205` (`exitProcess(0)`); `AppContext.kt:60-67`; `AppMain.kt`

- **Exit** calls `exitProcess(0)` directly. Active downloads are not paused, so up to 5 s of progress
  state is lost, and a streaming download in `ASSEMBLING` leaves a partial output. Records remain
  `DOWNLOADING` in `active-downloads.dat`; they load back as paused, which is fine, but the state is stale.
- If port 8597 is already taken (a second instance, or another app), `onFailure` only prints
  "Unable to start server". The UI never starts and the non-daemon GC thread keeps an **invisible
  process** alive.
- There is no single-instance handling, so a second launch does not bring the first window forward.

**Fix:** Add an `AppContext.shutdown()` that stops all sessions, waits (with a timeout) for the
pause callbacks, saves the DBs and config, stops the scheduler and server, then exits. Register it
from the menu and via `Runtime.addShutdownHook`. On bind failure, send `/show` (a new endpoint) to
the existing instance and exit, or show an error dialog and exit.

### B15 (P2): Threading violations
- `AppInstance.addDownload` (refresh path, `AppInstance.kt:122-127`) runs on the HTTP server thread
  and calls `refreshLinkWindow.dispose()` and `MessageBox.show(...)` off the EDT.
- `AppInstance.showAppWindow`, `showSchedulerWindow` and `showPropertiesWindow` do not marshal to the
  EDT. `showAppWindow` is called from the tray listener and the AppReopened listener.
- `DownloadManager.stopDownload` reads and mutates `queue` (a non-thread-safe `ArrayDeque`) without
  `synchronized(queue)`. `toDelete` is a plain `mutableSetOf` that the UI and engine threads touch concurrently.
- `AppDB.getById` and `size` are not synchronized, while `removeItem` rebuilds `indexMap`. A
  concurrent `getById` can return the **wrong record**, or throw `IndexOutOfBounds`.
- `AppInstance.updateDownloadInView` resolves `index` on the calling thread but uses it later on the
  EDT; a delete in between points it at a different row.
- A resumed HTTP context loads `chunks` into a `HashMap` (`StateSaver.readChunks`), while a new one
  uses `ConcurrentHashMap`, so behaviour differs between fresh and resumed downloads.

**Fix:** Wrap each public `IAppInstance` method in `runOnUIThread`. Guard `queue` and `toDelete`
with a single lock, or use `ConcurrentLinkedDeque` / `ConcurrentHashMap.newKeySet()`. Make every
`AppDB` accessor `@Synchronized`, and pass ids (not indexes) to the EDT, resolving the index there.
Use `ConcurrentHashMap` in `readChunks`.

### B16 (P2): HTTP client correctness
**Where:** `HttpClientImpl.kt:86, 101, 108-109`

- `contentType = body.contentType()?.type` gives only `"video"`, not `"video/mp4"`. Filename and
  extension inference (`getFileName`, `isMp4()` in streaming) receive the wrong value. Use
  `body.contentType()?.let { "${it.type}/${it.subtype}" }` or the raw `Content-Type` header.
- Cookies are joined with `";"`; the RFC 6265 separator is `"; "`.
- `lastModified = LocalDateTime.now()` should parse the `Last-Modified` header. This is also what
  the unimplemented "use server time" setting needs (F9).
- Proxy username and password are stored but never passed on. OkHttp does not use
  `java.net.Authenticator` for proxies, so set `.proxyAuthenticator { _, resp -> ... Credentials.basic(user, pass) }`.
- ~~`VideoHelper.httpClient` was built once at class init with the proxy settings of that moment.~~
  Fixed with S2: it is rebuilt when the proxy or certificate setting changes.

### B17 (P2): Secrets written to the log
**Where:** `BrowserIntegration.kt:60,74,88` (logs the raw extension JSON, including `cookie` and
`requestHeaders`); `HttpClientImpl.kt:101` (logs all response headers, including `Set-Cookie`);
`HlsDownloaderTask.kt:214, 260` (logs headers and the AES key object); `AppConfig` stores `proxyPass`
in plaintext.

**Fix:** Log only the URL and message type, and redact `cookie`, `authorization` and `set-cookie`.
Never log key material. Store the proxy password in the OS keychain, or at least obfuscate it and
restrict the config file permissions to `600`.

### B18 (P2): Hardening the local HTTP server
**Where:** `HttpServer.kt`, `HttpParser.kt`

- No limit on `Content-Length`: a single request can allocate gigabytes (`ByteArrayOutputStream`).
- No `soTimeout`, and one unbounded thread per connection: idle keep-alive sockets pile up threads.
- A JSON parse error in a handler throws, the connection closes with **no response**, and the
  extension marks the app as disconnected.
- `requestPath` includes the query string, so `/sync?x=1` does not match in `handleRequest` (the S1 method check already strips it).

**Fix:** Cap the body (for example at 4 MB) and reply 413. Set `socket.soTimeout = 15_000`. Use a
bounded daemon thread pool. Wrap `requestListener` in try/catch that replies 400/500. Strip the query
from the path.

### B19 (P2): `VideoHelper` issues
**Where:** `xdm-app/.../integration/VideoHelper.kt`

- `ManifestUtils.downloadManifestAsFile` creates files with `File.createTempFile(...)` in the system
  temp dir. `processHLSVideo` and `processDashVideo` never delete them, so one file leaks per
  detected manifest. `downloadManifestBytes` returns **null** if deleting the temp file fails,
  even though the bytes were read (`ManifestUtils.kt:28-36`).
- `m3u8MpdTabs`, `suspectedMp4Fragments` and `referersToSkip` only ever grow. Once a tab has shown
  an m3u8, plain HTTP videos in that tab id are suppressed forever, even after navigation.
- `thread { ... }` starts one new thread per media message, with no bound.
- `getHeader(CONTENT_LENGTH)?.toLong()` throws on a malformed value, which aborts the message.
- `processDashVideo`: `audio!!.mimeType` throws if a period has neither video nor audio.
- `contentType ?: false` (line 56) does nothing.
- The DASH and HLS tasks set `origin = null`, so "Refresh link" cannot work for streaming downloads.
- `CapturedVideoTracker.updateMediaTitle` renames HTTP and HLS entries but skips DASH.
- `CapturedVideoTracker.addVideoDownload` reads the maps without `synchronized(this)`.

**Fix:** Delete manifest temp files in `finally`, or better, parse from bytes in memory. Replace the
sets with size-bounded LRU maps keyed by tab id and cleared when the tab URL changes. Run processing
on a small shared executor. Use `toLongOrNull()`. Pass `msg.tabUrl` as `origin`. Add the DASH branch
to `updateMediaTitle`.

### B20 (P2): Failures appear as "Paused"
**Where:** `DownloadManager.kt:174-187`

`onDownloadFailed` sets `RecordStatus.PAUSED`. `RecordStatus.ERROR` exists but is never set, so the
list cannot tell failed downloads from paused ones, and the error reason is lost once the progress
window closes. There is also no automatic retry for transient failures (`NetworkError`).

**Fix:** Set `ERROR`, persist the last `DownloadError` in `DbRecord` (with a version bump in the
record format), show it in the row tooltip and Properties dialog, and offer "Retry". Optionally
auto-retry `NetworkError` with backoff.

### B21 (P3): Smaller issues
- **`/tab-update` is sent but never handled**: both extensions POST it from `onTabUpdate`, but `BrowserIntegration.handleRequest` has no case for it, so `CapturedVideoTracker.updateMediaTitle` is never called. The extension only sends it when `msg.tabsWatcher` from `/sync` matches, and `ConfigDto` has no `tabsWatcher` field (only `matchingHosts = emptyList()`), so it is dead on both sides. Add the route plus a `tabsWatcher` list in `ConfigDto`, or remove the feature.
- **`writeUTF` 64 KB limit**: `DataOutputStream.writeUTF` throws `UTFDataFormatException` above
  65,535 bytes. Large cookie strings or long signed URLs make `saveState` / `saveHttpTask` fail
  (the error is only logged), and the download then cannot resume. Write length-prefixed byte
  arrays instead.
- **No format versioning** in `.state`, `.info`, `*-downloads.dat` or `schedule.dat`. Any field
  change breaks existing user data. `AppConfig.load` tolerates only trailing fields, and a corrupt
  file leaves config **partially overwritten** (fields assigned before the exception). Load into a
  temporary object and apply it only on success. `SortKey.entries[input.readInt()]` can go out of range.
- `HttpChunkRetriever`: `CopyResult.Retry` never increments `retryCount`, so a connection that
  keeps dropping mid-stream retries forever at a fixed 5 s with no backoff. `Thread.sleep` does not
  check for cancel. `StreamingChunkRetriever` retries IOExceptions with no limit at all and ignores
  `config.maxRetries`. Segment failures are never retried at task level (that code is commented out
  in `onChunkComplete`), so one bad segment fails the whole HLS/DASH download after all the others finish.
- `StreamingChunkRetriever`: on a resumed segment, `piece.length.set(contentLength)` stores the
  *remaining* length instead of the total, so progress is wrong after a resume.
- HLS: a live playlist (no `#EXT-X-ENDLIST`) is silently downloaded as a snapshot. Detect this and
  either tell the user or implement live recording (F11).
- `DownloadManager.updateDownloadInfo` calls `saveState` (rename-based write) **inside**
  `readTransacted` while the same file is open. On Windows the rename fails; it currently works only
  because the `.bak1` preference in B9 hides it. Read first, close, then write.
- `DownloadScheduler`: a `ONE_TIME` entry whose minute passed while the app was closed never fires
  and is never removed. Entries for deleted downloads are never cleaned up.
- `populateSaveInFolders`: `coerceIn(1, folders.size - 1)` throws if `recentFolders` ever has fewer
  than 2 entries, for example if the `ND_AUTO_CAT` text equals the default folder after `distinct()`.
- `AppDB.loadActiveRecords` loads records with `paused = true` (intentional), but `lastActiveCount`
  and the other count fields and the `res` values are unused, as the `//TODO: Check errors` notes say.
- Leftover debug output: `println("Not implemented yet")` in `startDashDownload`, which *is*
  implemented; `println("Thread: ...")` in `showProgressError`; `print(r)` in `HttpChunkRetriever.connect`.
- `HttpDownloaderTask.throttleIfNeeded(id)` is `TODO("Not yet implemented")`. It is unused today
  but part of the `ChunkController` interface, so any future call crashes. Remove it from the
  interface or delegate to `throttle`.
- `DownloadType.Torrent -> TODO()` in 4 places (`DownloadManager.kt:81, 283, 364`,
  `AppFileUtil.kt:41`) throws `NotImplementedError` if a record ever has that type, for example a
  corrupted DB. Replace it with a logged failure. `Hds` and `Hss` are referenced in CLAUDE.md but not in the enum.
- CLAUDE.md is out of date: it does not mention the Matroska writer and demuxer (`transmux/mkv/`),
  which `TransmuxingMuxer` uses for `.mkv` output.
- Commented-out constructors and legacy Java code (`AppMenuHandler`, `AppWindow`,
  `MacUtils`/`LinuxUtils` startup helpers) make it harder to see what is live. `FFmpegMuxer` and
  `QueueService.kt` (empty) are dead.
- Test coverage: `xdm-app` has only placeholder tests (`AppTest.kt`, `AppMainTest.java`). None of
  `DownloadManager`, `AppDB`, `TaskInfoDB` round-trips, `AtomicIO` crash recovery, or the integration
  server are tested; most bugs above are in these untested classes.

---

## 2. Incomplete or stubbed features

| # | Feature | State | Where | Suggested implementation |
|---|---------|-------|-------|--------------------------|
| F1 | **Scheduler** | The UI saves schedules and the ticker runs, but `triggerScheduledDownload` only logs, so scheduled downloads **never start** | `DownloadScheduler.kt:120-123` | Call `AppContext.downloader.resumeDownload(id)`. Also catch up missed `ONE_TIME` entries at startup (fire if `epochMillis` has passed and it is within a grace window, otherwise drop), and remove entries on delete (B12). Consider a "stop at" time as well. |
| F2 | **Queues** | `QueueManager.attachToQueue` is empty, `QueueService.kt` is empty, and Start/Stop queue handlers are commented out | `Queues.kt`, `AppMenuHandler.kt:127-137, 338-348` | Model `DownloadQueue(id, name, downloadIds, maxParallel, schedule)`, persist it with `AtomicIO`, and have `DownloadManager.pumpQueue()` (B5) choose per queue. The scheduler can then start or stop whole queues. |
| F3 | **Restart download** | No-op | `AppMenuHandler.kt:81-83`, `AppWindow.kt:142` | Purge the `.state` and temp data (keep `.info`), reset the record to `READY`, and start again. |
| F4 | **Change file name / Save as** before completion | Commented out; context menu item hidden | `AppMenuHandler.changeFile`, `MainListView.kt:181, 197` | For non-finished records, update `fileName` / `userSelectedDownloadFolder` in `.info`. The commit step already reads them. |
| F5 | **Copy file** (to clipboard) | Hidden and no-op | `MainListView.kt:186, 202` | Put the file on the clipboard with `DataFlavor.javaFileListFlavor` via `Transferable`. |
| F6 | **Refresh link for HLS/DASH** | `getOriginPage` handles HTTP only; `updateDownloadInfo` accepts only an `HttpDownloadTaskInfo`, and `/vid` refresh only matches HTTP videos | `DownloadManager.kt:590-621`, `AppInstance.kt:137-143` | Persist `origin` (B3). When refreshing a streaming task, match the new manifest by quality and variant and replace the segment URLs in the `.state` chunks, keeping the downloaded ones. |
| F7 | **Link expiry detection** | `//TODO: Check for link refresh` ×3 | `CapturedVideoTracker.kt:29,38,45` | Store the capture time; if an entry is older than N minutes, re-probe it with HEAD or a small GET and prompt the user to reload the page if it fails. |
| F8 | **Auto-start video downloads** | `// TODO: Check if download window needs to be shown` | `AppInstance.kt:145` | Honour `startDownloadAutomatically` for `/vid` as `addDownload` already does. |
| F9 | **Settings that do nothing** | `getServerTime`, `overwriteExistingFiles` (UI only), `autoRenameOnConflict`, `shutdownAfterAllDone` (never read; duplicates `haltAfterDownload`), `proxyUser`/`proxyPass` (never sent, B16) | `AppConfig.kt`, settings panels | Implement: set the file mtime from `Last-Modified`; in `renameFile`, overwrite or rename based on the flags; remove `shutdownAfterAllDone`; add the proxy authenticator. Otherwise hide the controls. |
| F10 | **Toolbar menu items with no action** | `MENU_LANG`, `MENU_UPDATE`, `MENU_HELP_SUP` handlers are empty | `AppToolBar.kt:189-199` | Language: show a picker, set `config.lang`, restart. Update: call `UpdateChecker.checkForUpdate` (it already exists) and show `UpdatePanel`. Help: `openWebPage(...)`. |
| F11 | **Live HLS / dynamic DASH** | Dynamic MPD is rejected; live HLS is snapshotted silently | `MpdParser.kt:40`, `HlsParser` | Detect it first and report "live streams not supported". Recording can come later: re-poll the playlist every target duration and append new segments until stopped. |
| F12 | **SAMPLE-AES / DRM** | Rejected explicitly (reasonable) | `HlsParser.kt`, `MpdParser.kt:71` | Show the user a clear "protected content" message instead of a silent failure in the extension list. |
| F13 | **Main menu bar and legacy actions** | `createMainMenu()` is never called; its handler (Import/Export list, Delete completed, Speed limiter, Properties, Options, About, Help) is almost all commented out | `AppWindow.kt:117-212, 217+` | Decide whether to keep a menu bar (macOS users expect one) and wire it to the existing dialogs, or delete the dead code. Import/Export: serialize `DbRecord` + `.info` to JSON. Delete completed: purge finished records (B12). |
| F14 | **Torrent / HDS / HSS** | Enum and `TODO()` stubs only | `Models.kt`, `DownloadManager` | Remove them until they are planned, so `when` branches don't need `TODO()`. |
| — | `DownloadsDB` error handling | `//TODO: Check errors` ×6 | `DownloadsDB.kt:122-186` | Log `onFailure`. On a load failure, keep the corrupt file (rename it to `.corrupt`) instead of silently overwriting it with an empty list on the next save. |
| — | Filename update on init | `//TODO: Check if only ext to be updated` | `DownloadManager.kt:72` | When the user set a name explicitly (`respectFileName`), only fix the extension; don't replace the whole name with one derived from the server. |
| — | FFmpeg requirement check | `//TODO: Check FFmpeg required` | `NewVideoDownloadWindow.kt:221` | Obsolete, since the transmuxer replaced ffmpeg; remove the TODO. Optionally warn about unsupported codecs using `UnsupportedCodecException` before starting. |
| — | Extension `launchApp()` | Empty | `connector.js:52` | Use native messaging, or a custom URL scheme (`xdm://`) registered by the installer, to launch the app when it is disconnected. |
| — | Firefox extension | Still Manifest V2 (v1.4), while Chrome is MV3 (v3.3) | `browser-extension/firefox-extension` | Port it to MV3 with the shared code. |

---

## 3. Suggested order of work

1. **Security:** S1 and S2 are done. Run the manual checks listed in each section.
2. **Hangs and data loss:** B1, B2, B9, B3 (persist headers and cookies with a version header), B4.
3. **Queue and lifecycle:** B5 + F1 + F2 together (scheduler and queues depend on a correct pump),
   then B6, B7, B14.
4. **Resource hygiene:** B10, B11, B12, B13, B18.
5. **Polish:** B15–B21, remaining F-items, dead-code removal, CLAUDE.md update.
6. **Tests to add alongside:** `TaskInfoDB` / `AppDB` / `AtomicIO` round-trip and truncated-file
   recovery; `DownloadManager` queue limits using a fake `DownloaderTask`; integration server header
   format and the S1 origin/method rejection (403/405 cases); resuming encrypted HLS (extend `TestHlsE2E`); a 429 and
   stalled-read case in `MockHttpServer`.

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
| B1 | P2 | HTTP engine | ~~Read→write lock upgrade deadlocks the chunk thread on resume~~ **Fixed:** complete chunks marked Finished on restore; `isAlreadyDone` uses the write lock; regression tests added |
| B2 | P0 | HTTP engine | ~~An HTTP 429 response kills the chunk thread and the download hangs forever~~ **Fixed:** 429 retries after a capped Retry-After; unexpected errors fail the chunk; retry wait honours Pause |
| B3 | P1 | Persistence | ~~Queued and "download later" tasks lose cookies, headers and origin~~ **Fixed:** persisted for HTTP, HLS and DASH; `.info`/`.state` strings no longer limited to 64 KB |
| B4 | P1 | HLS | ~~Encrypted HLS always fails after a pause or restart (AES keys are only held in memory)~~ **Fixed:** keys stored in an owner-only `<id>.keys` file until decrypted; new `DecryptionError`; `.state`/`.info` owner-only |
| B5 | P1 | Queue | ~~HLS/DASH ignore `maxParallelDownloads`, and duplicate callbacks start too many queued downloads~~ **Fixed:** every start goes through one locked `pumpQueue()` for HTTP, HLS and DASH |
| B6 | P1 | Video | ~~Downloading the same detected video twice reuses the same task id~~ **Fixed:** each video download is a copy with a fresh id; duplicate ids are replaced on start |
| B7 | P1 | Commit | ~~`File.renameTo` fails across drives, so HLS/DASH to another volume fails with "disk space error"~~ **Fixed:** streaming muxes into the destination folder; safe cross-drive move; real failure reasons; no re-mux on commit retry |
| B8 | P1 | Integration | ~~Malformed HTTP response headers from the local server~~ **Fixed:** well-formed HTTP/1.1 responses; case-insensitive request headers; correct keep-alive |
| B9 | P1 | Persistence | ~~`AtomicIO` reads a half-written `.bak1` in preference to the good file~~ **Fixed:** completion footer; newest complete copy is read; failed saves are reported |
| B10 | P2 | Resources | ~~Thread pools and OkHttp clients leak for every streaming download and every paused HTTP download~~ **Fixed:** pools shut down and clients closed on success, failure and pause; paused HLS/DASH thread no longer hangs; background threads are daemon |
| B11 | P2 | HTTP engine | ~~`readTimeout = 0`, so a stalled connection blocks forever and Pause cannot unblock it~~ **Fixed:** configurable read timeout (Advanced settings); stall retries are limited; Pause cancels open calls |
| B12 | P2 | Cleanup | ~~Deleting a download never removes `task-<id>.info` / `<id>.state`; `AppDB.clear()` is also broken~~ **Fixed:** delete and Clear share one purge (temp data, `.info`, `.state`, keys, schedule); assembling/error downloads can be deleted |
| B13 | P2 | UI | ~~"Clear" wipes the DB while downloads are still running~~ **Fixed:** Clear removes only finished, paused and failed downloads; running, assembling and queued ones are kept |
| B14 | P2 | App lifecycle | Exit does not pause downloads or save state; a failed server bind leaves a headless JVM running |
| B15 | P2 | Threading | ~~Several UI calls run off the EDT; `queue` and `toDelete` are accessed without synchronization~~ **Fixed:** `IAppInstance` calls marshal to the EDT and resolve rows by id there; `AppDB` accessors synchronized; `queue`/`toDelete` guarded; resumed chunks use `ConcurrentHashMap` |
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

### B1 (P2, was P0): Deadlock when resuming a chunk that is already complete (FIXED)
**Where:** `HttpChunkRetriever.kt` (`isAlreadyDone`) and `HttpDownloader.kt` (`makeContext`)

**Status:** Fixed in the working tree by normalizing chunk status when saved state is restored, and
by taking the write lock in `isAlreadyDone`.
Covered by `TestHttpResumeCompletedChunk`, whose tests fail on the old code (with a thread dump of
the stuck thread) and pass with the fix.

#### The problem
`isAlreadyDone()` held `context.read {}` and called `controller.onChunkFinished(id)`, which takes
`context.write {}` (`HttpDownloader.kt:189`). `ReentrantReadWriteLock` cannot upgrade a read lock to
a write lock, so the thread parked on itself. Every other chunk thread (a queued writer blocks new
readers) and `stop()` then blocked on the same lock: the download froze and Pause did nothing until
the app was restarted.

A latent second bug sat behind it: the chunk was never marked `Finished`, so even without the
deadlock `onChunkFinished` would return early ("some chunk not Finished") and the download would sit
at 100%. That path could not run while the deadlock came first, but a naive fix would have exposed it.

#### Why it was rare (severity lowered to P2)
It needs a `.state` file with a chunk where `downloaded == length` but status is not `Finished`:
- **Pause** that saves state in the sub-millisecond gap between a chunk's last byte-count update
  (`HttpChunkRetriever.kt` `write { downloaded += read }`) and `status.set(Finished)`. Each chunk
  passes through that gap once per download, while a pause almost always lands in
  `inputStream.read()`.
- **Crash or quit** (see B14) after the 5 s periodic save happened to fire on a chunk's last
  progress update and before any later save.

The impact was still severe when it hit, but it is not something a normal user hits regularly.

#### The fix
Two layers, both in the working tree.

**1. Restore time (`HttpDownloader.kt`, `makeContext`).** A loaded state goes through
`normalizeRestoredChunks` before the task uses it:
```kotlin
private fun normalizeRestoredChunks(ctx: HttpTaskContext) {
    if (!ctx.init.get()) return
    ctx.chunks.values.forEach { c ->
        val len = c.length.get()
        if (c.status.get() != ChunkStatus.Finished && len > 0 && c.downloaded.get() >= len) {
            c.status.set(ChunkStatus.Finished)
        }
    }
}
```
- If every chunk is complete, `resume()` sees all `Finished` and calls `finishDownload()` without
  starting any chunk thread.
- Otherwise `startChunks()` (and `retryFailedChunk`) skip `Finished` chunks, so no thread is started
  for a complete chunk.
- The check matches `isAlreadyDone` (`init`, `length > 0`), so chunks of unknown length are left alone.
- No lock is needed: nothing else references the context yet.

**2. At the source (`HttpChunkRetriever.kt`, `isAlreadyDone`).** It takes the write lock instead of
the read lock and marks the chunk before notifying:
```kotlin
context.write {
    val chunk = context.chunks[id] ?: return true
    if (chunk.status.get() == ChunkStatus.Finished) return true
    val len = chunk.length.get()
    val downloaded = chunk.downloaded.get()
    if (context.init.get() && len > 0 && len - downloaded <= 0) {
        chunk.status.set(ChunkStatus.Finished)
        controller.onChunkFinished(id)
        return true
    }
}
```
- The write lock is reentrant for its holder, so `onChunkFinished`'s own `write {}` succeeds.
- Marking `Finished` and calling `onChunkFinished` under one lock mirrors the `CopyResult.Done` path,
  so only the last chunk to be marked sees every chunk finished and the file is committed once.
- It protects any future caller that restarts a complete chunk, which layer 1 does not.
- `isAlreadyDone` runs once per connection attempt, so the exclusive lock costs nothing measurable.

An earlier attempt at layer 2 released the read lock, marked `Finished`, and only then called
`onChunkFinished`. It was dropped because it opened a double-commit race: `onChunkFinished` checks
`completed` outside the write lock and not again inside it, so two threads could both pass the check
and both commit, and the second commit (temp file already moved) reports `DiskSpaceError` after
success. Doing both under one write lock, as layer 2 now does, closes that gap. Re-checking `completed` inside the
write block would still be a cheap hardening.

Holding the write lock while `onChunkFinished` commits the file is intentional: it keeps `stop()`,
takeover/split and `saveState` from changing or persisting state mid-commit. At that point every
chunk is `Finished`, so no downloading thread is blocked.

#### Tests (`TestHttpResumeCompletedChunk`)
| Test | Old code | Fixed |
|---|---|---|
| `resume_chunkCompleteButNotFinished_commitsFile`: both chunks complete, one not `Finished` | Deadlock, never commits | Commits without connecting, file matches |
| `resume_chunkCompleteButNotFinished_pauseStillWorks`: same state, then Pause | `stop()` blocks forever | Completes or pauses |
| `resume_oneChunkCompleteOtherPending_downloadsOnlyTheRest`: one complete chunk not `Finished`, one empty | Deadlock; the pending chunk also freezes | Downloads only the second half, file matches |
| `isAlreadyDone_completeChunkNotFinished_finishesWithoutRestoreFix`: undoes layer 1 on the loaded context and runs a retriever for the complete chunk | Deadlock | Chunk marked `Finished`, file committed |

### B2 (P0): HTTP 429 kills the chunk thread (FIXED)
**Where:** `HttpChunkRetriever.kt` (`connect`, `retrieveChunk`)

**Status:** Fixed in the working tree. Covered by `TestHttpRetryHandling`; all three tests failed on
the old code and pass with the fix.

#### The problem
On 429 the code ran `throw IOException("Rate limit hit")` inside the inline `onSuccess` lambda. The
exception escaped `connect()` and `retrieveChunk()`, which had no try/catch, so the thread died. The
chunk stayed `Downloading` forever and was never counted as failed or finished, so the download hung
at a partial percentage with no error. The computed `retryAfter` was also thrown away. Any other
unexpected exception in the chunk thread had the same effect, and the retry wait was a single
`Thread.sleep` that ignored Pause.

#### The fix
- 429 now returns `ConnectResult.Retry(retryAfter)`, with `Retry-After` capped at
  `MAX_RETRY_AFTER_SECS` (60 s). It still counts against `maxRetries`.
- `retrieveChunk()` wraps the loop (now `retrieveChunkInternal()`) in `try/catch (Exception)`. If the
  download was not cancelled, it logs and calls `chunkFailed(DownloadError.InternalError)`, so the
  normal all-chunks-failed path reports the error.
- The retry wait is `sleepUnlessCancelled`, which sleeps in 200 ms steps and returns as soon as
  `isCancelled()` is true.

#### Tests (`TestHttpRetryHandling`)
| Test | Old code | Fixed |
|---|---|---|
| `rateLimited429_honoursRetryAfterAndCompletes`: first request 429 with `Retry-After: 1` | Thread dies after 1 request, download hangs | Retries after 1 s, file matches |
| `unexpectedException_failsDownloadInsteadOfHanging`: HTTP client throws | Thread dies, no callback | `onDownloadFailed(InternalError)` |
| `pauseDuringRetryWait_chunkThreadExitsPromptly`: every request 503, Pause during the 5 s wait | Thread keeps sleeping | Thread exits within 1.5 s, no further request |

### B3 (P1): Queued and deferred HTTP downloads lose cookies, headers and origin (FIXED)
**Where:** `TaskInfoDB.kt`, `StateSaver.kt`, new `util/BinaryIO.kt`

**Status:** Fixed in the working tree. Covered by `TestTaskInfoDB`; all eight tests failed on the old
code and pass with the fix. Full test suite passes.

#### The problem
`saveHttpTask` persisted only url, fileName, flags, folder, maxPiece and size, and `getHttpTask`
returned `cookie = null, headers = null, origin = null, userSelectedDownloadFolder = null`. HLS and
DASH had the same gap. Paths that build the task from the `.info` file with no `.state` yet started
**without auth**:
- `processNextQueue` → `startHttpTask(id)`. The default `maxParallelDownloads = 1`, so every second
  concurrent browser download was affected.
- "Download later" (`runNow = false`) → `resumeDownload` → `resumeImmediately`.
- Any download whose `.state` was never written.

The result was 401/403 responses, or an HTML login page saved as the file. Load-modify-save paths
(`DownloadManager.kt` progress/init updates and `updateDownloadInfo`, i.e. "Refresh link") also
wiped these fields, so a refreshed cookie was thrown away. `getOriginPage` fell back to the file URL.

Separately, every string in `.info` and `.state` files used `writeUTF`, which throws above 64 KB. A
large cookie or long signed URL made the save fail (only logged), leaving no `.info` file (the
queued download silently never starts) or no `.state` file (the download cannot resume).

#### The fix
- **`TaskInfoDB`**: all three `save*Task` / `get*Task` pairs now write and read `cookie`, `headers`,
  `origin` and `userSelectedDownloadFolder`, appended after the type-specific fields through one shared
  `writeRequestFields` / `readRequestFields` pair, so the order stays in lockstep. `save*Task` now logs
  a failed write instead of ignoring the `Result`.
- **`util/BinaryIO.kt`** (new): `writeLongString` / `readLongString` (`Int` byte length + UTF-8
  bytes, with a 64 MB sanity cap on read), nullable variants, and `writeNullableHeaders` /
  `readNullableHeaders`. This replaces the private header helpers that were in `StateSaver.kt`.
- **64 KB limit**: every string in `.info` files (task info, DASH segment URLs) and `.state` files
  (HTTP/HLS/DASH contexts, chunks, segment/key URLs, IVs, headers, cookies, temp paths) now uses the
  length-prefixed encoding. `.state` headers still load as an empty map when absent, as before.
- **No format versioning**, by decision. Existing `task-<id>.info` and `<id>.state` files written by
  earlier builds cannot be read by this build (and vice versa): unfinished downloads from before the
  upgrade will not resume and need to be re-added.

Not changed:
- `authInfo` is still not persisted. It holds a password, and nothing in the app sets it today;
  storing it needs its own decision (e.g. the OS keychain).
- `*-downloads.dat` (file names, enum names), `schedule.dat` and the config file still use
  `writeUTF`. Their strings are file names, enum names, paths and user-typed settings, which cannot
  realistically reach 64 KB.

#### Tests (`TestTaskInfoDB`)
| Test | Old code | Fixed |
|---|---|---|
| `http_roundTripKeepsCookieHeadersOriginAndFolder` | Fields read back `null` | Equal |
| `hls_roundTripKeepsCookieHeadersOriginAndFolder` | Fields read back `null` | Equal |
| `dash_roundTripKeepsCookieHeadersOriginAndFolder` (incl. segments, MIME types) | Fields read back `null` | Equal |
| `loadModifySave_keepsFieldsItDidNotTouch` ("Refresh link" pattern) | Cookie wiped | Kept |
| `taskInfo_valuesOver64KbRoundTrip` (URL, cookie, header value) | Save fails | Equal |
| `httpState_valuesOver64KbRoundTrip` | Save fails, load `EOFException` | Loads |
| `hlsState_segmentUrlOver64KbRoundTrips` (segment URL, key URL, cookie) | Save fails, load `EOFException` | Loads |
| `queuedHttpTask_sendsPersistedCookieAndHeaders`: task loaded from the DB only, downloaded from `MockHttpServer` | Server got no `Cookie` | `Cookie` and custom header sent |

`MockHttpServer`'s `ReqCtx` now records request headers (new field with a default) for the last test.

### B4 (P1): Encrypted HLS fails after any pause or restart (FIXED)
**Where:** `HlsDownloaderTask.kt`, new `hls/HlsKeyStore.kt`, `StreamingDownloaderTask.kt`, `AtomicIO.kt`

**Status:** Fixed in the working tree. Covered by `TestHlsKeyPersistence`; all five tests failed on
the old code and pass with the fix. Full test suite passes.

#### The problem
`keyCache` was an in-memory map filled only in `initDownload()`. Every resume (after a pause or an app
restart) builds a new task from `.state`, where `context.init` is true, so `initDownload()` is
skipped and `keyCache` is empty. `decryptChunk` then threw "Key missing" inside `assemble()`, which
reached `download()` and was reported as `InternalError`. The user had to delete the download and
start again.

Found while fixing it:
- `retrieveKeys` ignored a key download that returned `null` (for example a 404): init succeeded,
  every segment downloaded, and the download only failed at the end with "Key missing".
- A wrong-size key was stored before the size check threw.
- `.state` and `.info` files were created world-readable (`rw-r--r--`), and they hold cookies and
  headers since B3.

#### The fix
- **Keys stored on disk, separately from `.state`** (`HlsKeyStore`): `retrieveKeys` writes all keys
  once to `<id>.keys` (count, then key URL + raw key bytes per entry). They are deliberately not part
  of `HlsTaskContext`, because `.state` is rewritten every few seconds during the download.
- **Loaded lazily on resume:** `postProcessChunks()` loads the file when the in-memory map is empty
  and some segment is still encrypted. Resume works even if the key URL has expired.
- **Deleted when no longer needed:** once every segment is decrypted, the task saves `.state` (so
  `encrypted = false` is recorded) and then deletes `<id>.keys`. `deleteTemp()` (deleting the
  download) also removes it.
- **Key fetch failures fail early:** a `null` download or a key that is not 16 bytes now fails
  `initDownload()` with `InvalidResponse` (or `TlsError`), before any segment is downloaded.
- **Separate error:** new `DownloadError.DecryptionError`. `decryptChunk` throws
  `DecryptionException` for a missing key or any decryption failure (wrong key, corrupt data), and
  `assemble()` reports it as `DecryptionError`. The progress window shows `ERR_DECRYPT` ("Unable to
  decrypt the video. The encryption key is missing or invalid.", added to `en.txt`).
- **Owner-only files:** `AtomicIO.writeTransacted` has a new `ownerOnly` flag that creates the temp
  file as `rw-------` on POSIX systems (the rename keeps it). `.state`, `.info` and `.keys` use it.
  Existing files become owner-only the next time they are saved. Windows is unchanged (per-user
  profile folders are already restricted).

Trade-off: while a download is paused or running, the AES key is on disk (owner-only) alongside
the cookies in `.state`. It is removed as soon as decryption finishes.

#### Tests (`TestHlsKeyPersistence`)
| Test | Old code | Fixed |
|---|---|---|
| `resumeAfterPause_keyUrlGone_decryptsWithStoredKey`: pause right after init, delete the key from the server, resume with a new task | `InternalError` ("Key missing") | Succeeds, output passes ffprobe |
| `keysFile_existsOwnerOnlyWhilePaused_andIsDeletedAfterSuccess` | No `.keys` file | `rw-------` while paused, deleted after success |
| `stateAndTaskInfoFiles_areOwnerOnly` | `rw-r--r--` | `rw-------` |
| `wrongKey_reportsDecryptionError`: server serves a different key | `InternalError` | `DecryptionError` |
| `keyNotFetchable_failsBeforeDownloadingSegments`: key URL returns 404 | Segments downloaded, then `InternalError` | `InvalidResponse`, init never completes |

The existing H7–H9 AES tests in `TestHlsE2E` still cover explicit IVs, sequence IVs and key rotation.

### B5 (P1): The parallel-download limit and queue are unreliable (FIXED)
**Where:** `DownloadManager.kt`

**Status:** Fixed in the working tree. Covered by `DownloadManagerQueueTest` (xdm-app); four of its
five tests failed on the old code and all pass with the fix (repeated runs stable). Full test suite passes.

#### The problems (all confirmed)
1. `startHlsDownload` / `startDashDownload` always started immediately and ignored
   `maxParallelDownloads`.
2. `processNextQueue()` started non-resume items only for `DownloadType.Http`; a queued HLS or DASH
   item was dequeued and dropped.
3. `processNextQueue()` ran **outside** the `activeSessions.remove(id)?.let {}` guard in the success,
   failed and paused callbacks, and never checked `activeSessions.size`. A duplicate or racing
   callback popped and started an extra download.
4. `resumeImmediately` caught the `!!` NPE for a missing task, left the record `READY`, and never
   advanced the queue.
5. Also found: `startHttpDownload` / `resumeDownload` checked `activeSessions.size` and started the
   task without a lock, so concurrent adds could exceed the limit; `stopDownload` read and changed
   `queue` without its lock.

#### The fix
- **One admission path:** `startHttpDownload` (when `runNow`), `startHlsDownload`, `startDashDownload`
  and `resumeDownload` save the task and record, append a `QueueItem`, and call `pumpQueue()`.
  Nothing else starts a task. `resumeDownload` ignores an id that is already running or queued.
- **`pumpQueue()`**, under `synchronized(queue)`: while `activeSessions.size < maxParallelDownloads`
  and the queue is not empty, pop the first item and `launch()` it; if that fails, `markNotStarted()`
  sets the record to `PAUSED` and the loop continues. When the queue and sessions are both empty it
  runs the existing halt-after-download check. Sessions are added only inside this lock and only
  removed in callbacks, so the limit cannot be exceeded.
- **`launch(item)`** handles HTTP, HLS and DASH uniformly: builds the task from `TaskInfoDB`, registers
  the session, updates the record and progress window, then `start()`s or `resume()`s it. It returns
  false when the record or task info is missing or the task throws. It replaces `startHttpTask`,
  `resumeImmediately` and the duplicated start code in `startHls/DashDownload`.
- **Callbacks** call `pumpQueue()` only inside the `activeSessions.remove(id)?.let {}` guard, after
  their `synchronized(appDB)` block (lock order is always queue → appDB).
- **`stopDownload`** removes queued items under the queue lock.
- **Testability:** `DownloadManager` takes an optional `taskFactory` (default builds the real tasks),
  so `AppMain` is unchanged.

Notes:
- The queue is strictly FIFO: a new download waits behind items already queued.
- A download that cannot start is shown as `PAUSED` because `RecordStatus.ERROR` is unused (B20).
- Tasks are rebuilt from `TaskInfoDB`, which does not persist `authInfo` (see B3); nothing in the app
  sets it today.

#### Tests (`DownloadManagerQueueTest`)
Real `DownloadManager` with real HTTP/HLS tasks pointed at a local socket that accepts and never
replies (so started downloads stay active), a real `AppConfig`/`AppDB`/`TaskInfoDB` in a temp dir,
and a no-op `IAppInstance` proxy. Internals are read by reflection.

| Test | Old code | Fixed |
|---|---|---|
| `hlsDownloads_respectParallelLimit`: limit 1, two HLS downloads | 2 active | 1 active, 1 queued |
| `queuedHlsDownload_startsWhenSlotFrees`: HTTP active, HLS added, HTTP paused | HLS started immediately | HLS queued, then starts |
| `duplicateFailureCallback_startsOnlyOneQueuedDownload`: A active, B and C queued, `onDownloadFailed(A)` twice | B and C both active | Only B active, C queued |
| `queuedResumeWithMissingTask_isMarkedPausedAndQueueContinues` | Queue stalls, record left `READY` | Record `PAUSED`, next download starts |
| `concurrentAdds_neverExceedLimit`: 8 threads add at once, limit 2 | Passed (the synchronized, fsync'd record save before the check lines threads up, so the race rarely shows) | Never more than 2 active |

### B6 (P1): Downloading the same detected video twice creates an id collision (FIXED)
**Where:** `DownloadManager.kt` (`addVideoDownload`, `startHttp/Hls/DashDownload`)

**Status:** Fixed in the working tree. Covered by `DownloadManagerVideoTest` (xdm-app); all five tests
failed on the old code and pass with the fix. Full test suite passes.

#### The problem
`addVideoDownload` changed the tracker's stored `HttpDownloadTaskInfo` / `HlsDownloadTaskInfo` /
`DashDownloadTaskInfo` in place (`fileName`, `autoCategorize`, `defaultDownloadFolder`; `tempDir` for
HLS/DASH) and passed that same object to `start*Download`. Picking the same video a second time used
**the same `id`**:
- the second download overwrote `task-<id>.info` and picked up the first download's `<id>.state`
  (and, for HLS, `<id>.keys`), so it resumed or re-finished the first download;
- `appDB.addActive` added a second record with the same id, so `indexMap` pointed at only one of
  them and pause, delete and progress acted on the wrong row;
- the tracker entry kept the last download's name and folder instead of what was detected.

#### The fix
- **`addVideoDownload`** starts a `copy` of the tracker entry with `id = CoreUtils.uniqueId()` and the
  chosen file name, folder and auto-categorize flag. The tracker entry is never modified. Each branch
  returns, so one video id starts one download.
- **Duplicate-id guard:** `startHttpDownload`, `startHlsDownload` and `startDashDownload` check
  `appDB` via `uniqueDownloadId()`; if a record already uses the id, they log it and continue with a
  copy that has a fresh id. This protects any future caller that reuses a task object.
- **HLS/DASH temp folder** is set on a copy (`tempFolder/<new id>`) instead of on the caller's object.

Not changed: "Refresh link" (`AppInstance.addVideoDownload`) only reads the tracker entry and updates
the existing download's id, and `updateMediaTitle` still renames detected entries on purpose.

#### Tests (`DownloadManagerVideoTest`)
Shares `DownloadManagerTestBase` with the B5 tests (real manager and tasks, a server that never
replies, real `CapturedVideoTracker`).

| Test | Old code | Fixed |
|---|---|---|
| `httpVideoDownloadedTwice_getsTwoIndependentDownloads` | Both records use the video id; one `.info` overwritten | Two ids, each `.info` keeps its own name and folder |
| `hlsVideoDownloadedTwice_getsSeparateIdsAndTempFolders` | Same id and temp folder | Different ids and temp folders |
| `dashVideoDownloadedTwice_getsSeparateIds` | Same id | Different ids |
| `trackerEntry_isNotModifiedByDownloading` (HTTP and HLS) | Name, folder and flags changed | Unchanged |
| `startWithIdThatAlreadyHasARecord_assignsNewId` | Two records with the same id | Second download gets a new id; the first `.info` is untouched |

### B7 (P1): Committing the output file fails across filesystems (FIXED)
**Where:** `DownloadManager.kt` (`renameFile`, `commitOutputFile`, `outputFilePath`),
`FileUtils.kt` (`moveFile`), `StreamingDownloaderTask.kt` (`assemble`), `HlsDownloaderTask.kt`,
`TransmuxingMuxer.kt`, `MkvWriter.kt`, `DownloadHost.kt`, `HttpDownloader.kt`

**Status:** Fixed in the working tree. Full test suite passes. See the test tables below for which
tests failed on the old code.

#### The problem
- **HLS/DASH:** segments, decrypted segments and the muxed output were all written under
  `config.tempFolder/<id>` (default `~/.temp`), and `commitOutputFile` then used `File.renameTo`
  into the download folder. `renameTo` cannot cross file systems, so saving to an external drive,
  another partition or a network share **always** failed.
- **HTTP:** the temp file is written in the download folder itself, so the move is to the same
  folder or a category subfolder. It fails across drives only when that subfolder is a mount point
  or a symlink/junction to another volume. It also failed for other reasons: an uncreatable
  category folder, the temp file locked by antivirus or an indexer on Windows, the destination
  appearing between the unique-name check and the rename, or the drive disappearing.
- **Every failure was reported as `DiskSpaceError`:** `CommitResult.Failed` carried no reason,
  including for "task not found" and a failed `mkdirs`.
- **`renameTo` risks:** it silently overwrites an existing destination on macOS/Linux, and any copy
  fallback could have left a partial file at the final name.
- **Disk use and retries:** peak use on the temp drive was segments + output (+ `.enc` copies for
  AES HLS, about 3×); a cross-drive copy would need space on both drives at once; and retrying a
  failed commit re-decrypted and re-muxed everything.

#### The fix
1. **Safe move** (`FileUtils.moveFile(src, dst, ops)`): atomic rename first; on
   `AtomicMoveNotSupportedException` it checks free space (`DiskSpaceError` if short), copies to
   `<dst>.part`, renames that into place on the destination volume, then deletes the source. It
   never overwrites an existing `dst`, and on any failure the source is kept and `.part` removed.
   `MoveOps` makes the file-system calls replaceable in tests.
2. **Real failure reasons:** `CommitResult.Failed(error)`. New `DownloadError.OutputWriteError`
   ("Unable to save the file to the destination folder.", `ERR_OUTPUT_WRITE`). The HTTP engine
   (`onChunkFinished`, `finishDownload`) and the streaming `assemble()` report `res.error`.
   `commitOutputFile`: uncreatable folder or failed move → `OutputWriteError`, not enough space →
   `DiskSpaceError`, task not found → `InternalError`. `renameFile` retries with the next unique
   name (up to 3 times) if a file appears at the chosen name.
3. **Mux directly into the destination folder:** new `FileProvider.outputFilePath(id, type, ext)`
   (default `null` = old behaviour). `DownloadManager` returns a hidden `.<id>.xdm-part<ext>` file in
   the resolved destination folder (category subfolder included) for HLS/DASH, and `null` for HTTP.
   `assemble()` creates the folder (failing early with `OutputWriteError` before muxing), muxes into
   that path, and the commit is a same-folder rename. Segments and decryption stay in the temp
   folder. `deleteTemp()` and deleting a paused download remove the partial output.
4. **MKV spool in the temp folder:** `MkvWriter(outputPath, spoolDir)`; `TransmuxingMuxer` now passes
   its (previously ignored) `tempDir`, so the destination only needs the output's size for WebM too.
5. **Commit retry without re-muxing:** if the state says the mux completed (`context.completed`) and
   the output file exists, `assemble()` skips decryption and muxing and only retries the commit.
6. **AES HLS `.enc` segments deleted** after every segment is decrypted and that state is saved
   (together with the key file), so peak temp use drops from about 3× to 2×.

Not done:
- No progress is shown during a cross-drive copy (now only reachable from the HTTP category-folder
  case or hosts that do not provide an output path).
- Write failures during muxing (for example a network share disconnecting) still come back from
  `mux()` as `false` and are reported as `MuxError`; telling them apart needs `mux()` to return a
  reason.
- The partial file is hidden with a leading dot, which Windows does not treat as hidden; cloud-sync
  clients may see it while muxing.

#### Tests
`xdm-app` `DownloadManagerCommitTest` (reflection for new API, so it compiles against the old code):

| Test | Old code | Fixed |
|---|---|---|
| `uncreatableDestinationFolder_reportsOutputWriteError` | Failed with no reason (shown as disk error) | `OutputWriteError`, temp file kept |
| `missingTaskInfo_reportsInternalError` | No reason | `InternalError` |
| `commit_movesFileAndNeverOverwritesExistingFile` | Passed (guard) | Passes: `file_1.bin`, existing file intact |
| `streamingOutputPath_isHiddenPartFileInDestinationFolder` (category folder) | No such API | `<folder>/Video/.<id>.xdm-part.mp4`; `null` for HTTP |
| `deletingPausedStreamingDownload_removesPartialOutput` | Partial file left | Removed |

`xdm-core` (new API, written with the fix, so they only run against the new code):

| Test | Checks |
|---|---|
| `TestFileMove.sameVolume_movesFile` | Content moved, source gone |
| `TestFileMove.otherVolume_copiesThenDeletesSource` (atomic move throws, as across file systems) | Copied, source gone, no `.part` |
| `TestFileMove.copyFailsPartway_leavesSourceAndNoPartialFile` | `OutputWriteError`, source intact, no destination or `.part` |
| `TestFileMove.notEnoughSpace_reportsDiskSpaceErrorAndWritesNothing` | `DiskSpaceError`, nothing written |
| `TestFileMove.destinationExists_isNeverOverwritten` | `OutputWriteError` on both paths, existing file intact |
| `TestStreamingOutputFolder.muxesIntoDestinationFolder_andCommitsFromThere` (ffmpeg HLS) | Commit receives the destination-folder path; output valid; no partial file or temp folder left |
| `TestStreamingOutputFolder.failedCommit_isRetriedWithoutMuxingAgain` | First run `OutputWriteError` with output kept; resume succeeds with a single mux call in total |
| `TestStreamingOutputFolder.unwritableDestination_failsBeforeMuxing` | `OutputWriteError`, muxer never called |
| `TestStreamingOutputFolder.encryptedSegments_areDeletedAfterDecryption` (AES HLS) | No `.enc` files left when committing |
| `TestStreamingOutputFolder.mkvSpool_isWrittenToSpoolDir` | Spool in the temp folder, not next to the output |

The test hosts in `HttpDownloadTestBase` and `StreamingE2EBase` were updated to
`CommitResult.Failed(DownloadError.OutputWriteError)`.

### B8 (P1): The integration server writes malformed response headers (FIXED)
**Where:** `xdm-app/.../integration/RequestContext.kt`, `HttpParser.kt`, `HttpServer.kt`

**Status:** Fixed in the working tree. Covered by `IntegrationHttpTest` (xdm-app); all eight tests
failed on the old code and pass with the fix. Full test suite passes.

#### The problem
`RequestContext.sendResponse` built `mutableListOf(headerLine, headerContents)`, a list containing a
list, with each entry formatted as `"$k : $v"` where `v` is a `List`. The bytes on the wire were:
```
HTTP/1.0 200 OK
[Cache-Control : [max-age=0, no-cache, must-revalidate], Content-Type : [application/json]]
Connection: keep-alive
Content-Length: 11
```
- All custom headers on one malformed line (in random `HashMap` order). Browsers tolerated it; a
  strict client does not: the JDK `HttpClient` fails with `Invalid header name "[Cache-Control "`.
- `HTTP/1.0` together with an unconditional `Connection: keep-alive`, even when the server was about
  to close the socket.
- `Content-Length` only when a body was set.

`HttpParser`:
- `Content-Length` and `Connection` were looked up case-sensitively, so a lowercase
  `content-length` body was ignored (and would corrupt the next request on the connection);
  `Connection: Keep-Alive` was not recognised.
- The HTTP version was ignored: an HTTP/1.1 request without a `Connection` header was treated as
  "close", although HTTP/1.1 is persistent by default.
- A client closing an idle kept-alive connection was logged as an error (`Unexpected EOF`).

Also found: `HttpServer.stop()` closed the listening socket but the accept loop kept running
(`while (true)`), spinning and logging `Socket is closed` forever.

#### The fix
- **`sendResponse`** writes `HTTP/1.1 <code> <message>`, one `Name: value` line per header value in
  insertion order (`LinkedHashMap`), always `Content-Length` (0 without a body), and
  `Connection: keep-alive|close` matching what the server will do with the socket.
- **`HttpParser`** stores request headers in a case-insensitive map, parses the request version, and
  decides keep-alive per HTTP/1.1 (persistent unless `close`) or HTTP/1.0 (only with `keep-alive`),
  ignoring case and handling comma-separated tokens. End of stream before a new request throws
  `ConnectionClosedException`, which `HttpServer` treats as a normal close. Blank lines before a
  request line are tolerated.
- **`HttpServer`** stops accepting once the server socket is closed.

Note: because HTTP/1.1 requests are now kept alive by default, idle connections hold their thread
until the client closes them. Browsers already send `Connection: keep-alive`, so behaviour for the
extension is unchanged; the missing socket timeout and thread-per-connection model remain B18.

#### Tests (`IntegrationHttpTest`)
Raw request bytes over a real local socket pair through `HttpParser` and `RequestContext`, plus an
end-to-end run against `HttpServer`.

| Test | Old code | Fixed |
|---|---|---|
| `response_usesHttp11StatusLine` | `HTTP/1.0 200 OK` | `HTTP/1.1 200 OK` |
| `response_writesEachHeaderOnItsOwnWellFormedLine` | One `[...]` line | `Content-Type: application/json`, `Cache-Control: ...` |
| `response_headerWithTwoValues_isSentAsTwoLines` | Printed as a list | Two `Vary:` lines |
| `response_withoutBody_stillSendsContentLength` | No `Content-Length` | `Content-Length: 0` |
| `response_connectionHeaderMatchesRequest` (`Connection: close`) | `Connection: keep-alive` | `Connection: close` |
| `parser_readsBodyWithLowercaseContentLength` | Body `null` | Body read |
| `parser_keepAliveFollowsHttpVersionDefaults` | HTTP/1.1 default and `Keep-Alive` → false | true; HTTP/1.0 default and `close` → false |
| `endToEnd_strictClientParsesResponsesOnOneConnection` (JDK `HttpClient`, two GETs) | `ProtocolException: Invalid header name` | Both succeed with `application/json` |

### B9 (P1): `AtomicIO.readTransacted` can prefer a corrupt file (FIXED)
**Where:** `xdm-core/.../util/AtomicIO.kt`; `DownloadManager.updateDownloadInfo`

**Status:** Fixed in the working tree. Covered by `TestAtomicIO`; six of its eight tests failed on
the old code and all pass with the fix (the other two are guards). Full test suite passes.

`AtomicIO` stores every persisted file: config, the three download lists, `schedule.dat`, `.state`,
`.info` and `.keys`.

#### The problem
Reading:
- Reads preferred `.bak1`, the in-progress temp file. A crash mid-write, or a writer that threw,
  left a truncated `.bak1`, and every later read picked it, failed, and never fell back to the
  intact file or `.bak2`. Config, the download list or resume state was lost until a later write
  succeeded.
- There was no fallback to `.bak2` when the main file was bad.
- A truncated file was not detected up front, and two readers have side effects while reading:
  `AppConfig.load` assigns fields one by one (a failure leaves config half overwritten, B21) and
  `AppDB.loadRecordsFromStream` adds records to the live list. So simply retrying another copy after
  an exception would have duplicated download records.
- Reads were unbuffered (`DataInputStream(FileInputStream)`).

Writing:
- `renameTo` results were ignored: if moving the new file into place failed (for example a locked
  file on Windows), `writeTransacted` still returned success and the data was never saved.
- A writer that threw left `.bak1` behind for the next read to pick up.
- Writes were unbuffered.

Callers:
- `DownloadManager.updateDownloadInfo` saved `.state` from inside the `readTransacted` reader, i.e.
  while the same file was still open for reading (a rename that fails on Windows, silently because of
  the point above).

#### The fix
- **Completion footer:** every save appends the 8 bytes `XDM-END!` after the writer returns, then
  flushes and `fsync`s. A copy that does not end with the footer was not completely written.
- **Reads** load the candidates `<name>`, `<name>.bak1`, `<name>.bak2` in that order and use the
  first one that ends with the footer (`.bak1` is complete only when a crash happened between the
  two moves, which is exactly when `<name>` is missing). Only that copy's bytes, without the footer,
  are handed to the reader through an in-memory stream, so the reader runs **once, on complete
  data**, and cannot leave partial side effects from a bad copy.
- **Files written before the footer existed** are still read: if no candidate has the footer, the
  first existing file (`<name>` before `.bak1`) is used as is. No versioning or migration is needed;
  each file gains the footer on its next save.
- **Writes** are buffered. If the writer throws, `.bak1` is deleted and failure is returned. The
  current file is kept as `.bak2` (best effort, `Files.move(..., REPLACE_EXISTING)`), then `.bak1` is
  moved into place with `ATOMIC_MOVE` (plain replace where atomic moves are unsupported). Any failure
  is returned as `Result.failure`.
- **`updateDownloadInfo`** reads the `.state` file first and saves it afterwards.

Why a footer and not a checksum (decided): files are never overwritten in place (each save writes a
new `.bak1`, syncs it, then renames), so the only corruption that can come from the app's own writes
is an incomplete file, which the footer detects. A footer does not detect bytes damaged inside a
complete file (bad sector, external tool); `.bak2` from the previous save remains available but is
only used when the main copy is incomplete. A unique id written as both header and footer was also
considered: it would additionally catch a torn in-place overwrite, which this write pattern cannot
produce, at the cost of a format change at the start of every file.

Remaining (B21): `AppConfig.load` still assigns fields as it reads, so an old footer-less config
file that is corrupt can still be half applied.

#### Tests (`TestAtomicIO`)
| Test | Old code | Fixed |
|---|---|---|
| `roundTrip_andFileEndsWithCompletionFooter` | No footer | Data read back; file ends with `XDM-END!` |
| `truncatedTempFile_isIgnoredInFavourOfIntactFile` | `EOFException` (read the partial `.bak1`) | Main file's data |
| `writerThatThrows_reportsFailureAndLeavesPreviousDataReadable` | Partial `.bak1` left behind | Failure reported, `.bak1` removed, previous data read |
| `incompleteFinalFile_fallsBackToBackup` | `EOFException` | `.bak2`'s data |
| `readerRunsOnlyOnCompleteData` (reader appends as it reads, like `AppDB`) | Reader ran on the incomplete file | Reader sees only the complete `.bak2` data |
| `failedCommit_isReportedAsFailure` (directories block the backup and final names) | Reported success | Reported failure |
| `crashBetweenRenames_readsCompleteTempFile` | Passed (guard) | Newest data from `.bak1` |
| `legacyFileWithoutFooter_isStillReadable` | Passed (guard) | Still readable |

### B10 (P2): Thread and connection leaks (FIXED)
**Where:** `StreamingDownloaderTask.kt`, `HlsDownloaderTask.kt`, `HttpDownloader.kt`, `HttpClientImpl.kt`, `AppMain.kt`

**Status:** Fixed in the working tree, keeping one HTTP client per task. Covered by
`TestHttpResourceCleanup` and `TestStreamingResourceCleanup`; all six tests failed on the old code
and pass with the fix. Full test suite passes.

#### The problems (all confirmed)
1. **Streaming thread pools never shut down.** `Executors.newFixedThreadPool(maxSegments)` threads are
   non-daemon and never time out, and the pool was shut down only in `stop()`. Every finished or
   failed HLS/DASH download left up to `maxSegments` idle threads until the app restarted.
2. **The streaming download thread hung on Pause.** `downloadChunks()` waited on `latch.await()`.
   `stop()`'s `shutdownNow()` drops segments that have not started, so they never count down and the
   thread blocked forever, keeping the whole task (context, segment list, client, muxer) reachable.
   This happened on nearly every pause (whenever there are more segments than `maxSegments`). The
   HLS manifest wait in `initDownload()` had the same pattern.
3. **Per-task HTTP clients were rarely closed.** HTTP closed the client only inside
   `onChunkFinished`; never on pause, when all chunks failed, or in `finishDownload()` (resume with
   every chunk done). Streaming tasks never closed theirs. Requests are synchronous and idle
   connections expire after 5 s, so the cost was mostly idle sockets and objects, but closing on
   pause also matters for B11. (Correction found while fixing B11: `dispatcher.cancelAll()` only
   cancels calls still inside `execute()`, so it does **not** unblock a thread already reading a
   response body. B11 made `close()` cancel every open call.)
4. **The periodic `System.gc()` thread was non-daemon** (normal exit uses `exitProcess`, so this only
   mattered when the app should end some other way, e.g. B14's failed server bind).

#### The fix
- **Streaming (`StreamingDownloaderTask`):**
  - `download()` has a `finally` that calls `executorService.shutdownNow()` and
    `context.httpClient.close()`, so pool and client are released on success, failure, exception
    and pause.
  - `stop()` closes the client right after `shutdownNow()`, cancelling in-flight segment reads.
  - New `awaitUnlessStopped(latch)` waits in 200 ms steps and returns `false` once `stopFlag` is set.
    `downloadChunks()` and `HlsDownloaderTask.initDownload()` use it instead of `latch.await()`.
    If the manifest wait is abandoned because of a pause, `download()` returns without reporting a
    failure.
- **HTTP (`HttpDownloaderTask`), client still per task:**
  - `stop()` closes the client after saving state and before `onDownloadPaused`.
  - `onChunkFailed` closes it after reporting that all chunks failed.
  - `finishDownload()` closes it in a `finally`.
  - The existing close in `onChunkFinished` (fresh download, commit success or failure) is unchanged.
  - `close()` can now run more than once (for example pause after success); that is harmless.
- **`HttpClientImpl.close()`**: `client.dispatcher.cancelAll()`, `shutdownNow()` on the dispatcher
  executor and `connectionPool.evictAll()` release the dispatcher and connection pool; the
  follow-up cleanup thread is now a daemon.
- **`AppMain`**: the periodic GC thread is named `periodic-gc` and is a daemon.

Not done, by choice: one shared `OkHttpClient` app-wide. If that is revisited, tasks would derive
per-task clients with `sharedClient.newBuilder()` (keeping proxy and `ignoreCertErrors` per task),
and a task's `close()` must then cancel only its own calls and never shut down the shared dispatcher.

#### Tests
| Test | Old code | Fixed |
|---|---|---|
| `TestHttpResourceCleanup.pause_closesHttpClient` | Client never closed | Closed |
| `TestHttpResourceCleanup.failure_closesHttpClient` (HTTP 500) | Client never closed | Closed |
| `TestHttpResourceCleanup.resumeWithAllChunksFinished_closesHttpClient` | Client never closed | Closed |
| `TestStreamingResourceCleanup.success_shutsDownPoolAndClosesClient` (ffmpeg HLS) | Pool still running | Pool shut down, client closed |
| `TestStreamingResourceCleanup.failure_shutsDownPoolAndClosesClient` (missing playlist) | Pool still running | Pool shut down, client closed |
| `TestStreamingResourceCleanup.pause_withQueuedSegments_downloadThreadExitsAndClientCloses` (20 slow segments, 2 threads) | Download thread parked on the latch forever | Thread exits, client closed |

The tests count `close()` calls with a `CountingHttpClient` wrapper around the real client. The
daemon flag on the GC thread has no test.

### B11 (P2): Stalled connections hang, and Pause does not abort sockets (FIXED)
**Where:** `HttpClientImpl.kt`, `CoreConfig.kt`, `HttpChunkRetriever.kt`, `StreamingChunkRetriever.kt`,
`StreamingDownloaderTask.kt`, `AppConfig.kt`, `AdvancedConfigPanel.kt`, `DownloadManager.kt`

**Status:** Fixed in the working tree. Covered by `TestHttpStalls` and `TestStreamingStalls`
(xdm-core); all five tests failed on the old code and pass with the fix. `AppConfigReadTimeoutTest`
(xdm-app) covers the new setting. Full test suite passes.

#### The problem
- `HttpClientImpl` used `readTimeout(0)` (wait forever). A server that stops sending data blocked
  `inputStream.read()` forever in HTTP chunks, HLS/DASH segments, and playlist/key downloads; the
  retry logic never ran.
- **Pause did not release a stalled read, even after B10.** `HttpClientImpl.close()` called
  `dispatcher.cancelAll()`, but OkHttp stops tracking a synchronous call once `execute()` returns the
  response headers, so a thread blocked reading the **body** was not cancelled.
- With a timeout alone, a dead server would have been retried forever:
  - HTTP: a failed body read returns `CopyResult.Retry`, but `retryCount` only counted connect failures.
  - HLS/DASH: `StreamingChunkRetriever` retried every `IOException` (including its own 429 handling)
    with no limit and ignored `maxRetries`.

#### The fix
- **Read timeout:** `HttpClientImpl(..., readTimeoutSeconds)` (default 60). `CoreConfig` gets
  `readTimeoutSeconds` with a default of `CoreConfig.DEFAULT_READ_TIMEOUT_SECONDS` (60), and
  `DownloadManager.newHttpClient()` passes the configured value. A stalled read throws a timeout and
  goes through the normal retry path.
- **Setting:** Advanced settings → Network → "Read timeout (seconds)" (spinner, 5–600, step 5, with a
  hint). Saved as a trailing field in the config (older configs keep 60; out-of-range values are
  clamped on load). New strings `SETTINGS_SEC_ADV_NETWORK`, `MSG_READ_TIMEOUT`, `MSG_READ_TIMEOUT_HINT`.
  New clients pick it up; downloads already running keep their client's timeout until resumed.
- **Pause cancels open calls:** `HttpClientImpl` tracks every call whose response is still open and
  `close()` cancels them, which closes the socket and makes the blocked read fail at once. Both engines
  already call `close()` on Pause (B10).
- **Limited stall retries, without penalising a slow but working link:**
  - HTTP: after a copy that ends in `Retry`, if the chunk received data during the attempt
    `retryCount` resets to 0; otherwise it is incremented and `onRetry` fails the chunk with
    `NetworkError` beyond `maxRetries`.
  - HLS/DASH: `StreamingChunkRetriever` takes `maxRetries` (from `CoreConfig`) and counts attempts
    that received no data (timeouts, connection errors, 429s); beyond the limit the segment fails with
    `NetworkError`, so the download fails instead of hanging.

#### Tests
| Test | Old code | Fixed |
|---|---|---|
| `TestHttpStalls.stalledConnection_timesOutAndResumes` (first connection stalls after 100 KB) | Hangs | Times out, resumes with a range request, file matches |
| `TestHttpStalls.serverThatNeverSendsData_failsAfterMaxRetries` (`maxRetries = 1`) | Hangs | `NetworkError` |
| `TestHttpStalls.pauseDuringStalledRead_releasesChunkThread` (600 s timeout, so only Pause can release it) | Chunk thread stays blocked in `SocketInputStream.read` | Released at once |
| `TestStreamingStalls.stalledSegment_timesOutAndIsRetried` (ffmpeg HLS) | Hangs | Retry succeeds, output passes ffprobe |
| `TestStreamingStalls.segmentThatNeverArrives_failsAfterMaxRetries` | Hangs | `NetworkError` |
| `AppConfigReadTimeoutTest` (3 tests: save/load, older config keeps default, clamping) | — (new setting) | Pass |

The tests create the client with a 1 s timeout through reflection (`httpClientWithReadTimeout`), so
they compile against the old code, where the client had no timeout.

### B12 (P2): Leftover files on delete; `AppDB.clear()` is broken (FIXED)
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

**Status:** Fixed in the working tree. Covered by `DownloadManagerDeleteTest` (xdm-app).
- `DownloadManager.deleteDownload` decides by whether the download has an active session, not by
  its status. An active download (downloading or assembling) is stopped and added to `toDelete`,
  and is purged in `onDownloadPaused`, or in `onDownloadFailed` if it fails first. If it finishes
  before the stop takes effect, `onDownloadSuccess` drops the record and metadata but keeps the
  file. Any other download (finished, paused, failed, queued) is dequeued and purged straight away.
  The active check and the dequeue happen under the `queue` lock, so a queued download cannot be
  launched while it is being deleted.
- `purgeFiles(rec)` deletes the temp data of unfinished downloads (the HTTP temp file or the
  streaming temp folder, plus a streaming download's partial `.mp4`/`.mkv`, resolved before the
  task info goes). `deleteMetadata(id)` removes `task-<id>.info`, `<id>.state{,.bak1,.bak2}`, the
  HLS `<id>.keys` and the schedule entry. `deleteAfterStopped` calls `task.deleteTemp()` and then
  `deleteMetadata`.
- `AppDB.clear()` is replaced by `AppDB.removeWhere(predicate)`, which removes the rows, rebuilds
  the index and saves all three lists. `removeItem` now saves the active list for `ASSEMBLING` and
  `ERROR` records too, and drops the id from the index.
- `TaskInfoDB.deleteRecord` also removes `task-<id>.info.bak1`.
- `AppContext.hasScheduler` lets the purge skip the scheduler when it is not set up (tests).

### B13 (P2): "Clear" while downloads are running (FIXED)
**Where:** `MainListView.kt:263-270` → `AppDB.clear()`

Clearing removes records while `activeSessions` keeps downloading. Later callbacks find no record,
and on completion the files are committed but never shown. The temp files of in-flight downloads
are orphaned.

**Fix:** Either stop all active sessions first and wait for `onDownloadPaused` before purging, or
make Clear remove only finished records (the conventional behaviour), with a separate "Delete all"
action.

**Status:** Fixed in the working tree. Covered by `DownloadManagerDeleteTest.clear_keepsActiveAndQueuedDownloads`.
Clear (`MainListView.clear`) calls `DownloadManager.clearInactive()`, which, under the `queue`
lock, removes every record that has no active session, is not queued or pending delete, and is not
`DOWNLOADING`, `ASSEMBLING` or `READY`. Each removed record is then purged as in B12. Finished files
in the download folder are kept. `MSG_CLEAR_CONFIRM` (en) now says that downloads in progress are
kept and files are not deleted. A separate "Delete all" action was not added.

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

### B15 (P2): Threading violations (FIXED)
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

**Status:** Fixed in the working tree. xdm-core and xdm-app test suites pass; there is no automated
EDT test.
- `AppInstance`: `showAppWindow`, `showRefreshWindow`, `showSchedulerWindow`,
  `showPropertiesWindow` and the refresh-link path of `addDownload` (dispose + message box) run
  through `runOnUIThread`.
- `updateDownloadInView(id)` and `addDownloadInView(id)` resolve the row index **on the EDT**. The
  unused `deleteDownloadInView(id: Long)` overload, which could never resolve a removed id, is
  gone. `deleteDownloadInView(index)` stays synchronous (`invokeAndWait`) and the list view
  refreshes the whole table on delete, so a shifted index does no harm.
- `AppDB.getById` and `size` are `@Synchronized` (the lock is the `AppDB` instance, as in
  `DownloadManager`'s `synchronized(appDB)` blocks). `getById` also checks that the row it finds has
  the requested id and uses `getOrNull`, so it never returns the wrong record or throws.
- `DownloadManager`: every `queue` access is inside `synchronized(queue)` (lock order
  queue → appDB); `toDelete` is `ConcurrentHashMap.newKeySet()`.
- `StateSaver.readChunks` builds a `ConcurrentHashMap`, like a fresh `HttpDownloader`.

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
- ~~**`writeUTF` 64 KB limit**~~ **Fixed with B3** for `.info` and `.state` files, which now use
  length-prefixed UTF-8 strings. The remaining `writeUTF` users (`*-downloads.dat`, `schedule.dat`,
  config) only store file names, enum names, paths and settings.
- **No format versioning** in `.state`, `.info`, `*-downloads.dat` or `schedule.dat`. Any field
  change breaks existing user data (the B3 fix changed the `.info` and `.state` layouts without a
  version, by decision, so older files are unreadable). `AppConfig.load` tolerates only trailing fields, and a corrupt
  file leaves config **partially overwritten** (fields assigned before the exception). Since B9,
  incomplete files never reach the reader (completion footer), so this remains only for old
  footer-less config files and for damage inside a complete file. Load into a
  temporary object and apply it only on success. `SortKey.entries[input.readInt()]` can go out of range.
- `HttpChunkRetriever` / `StreamingChunkRetriever`: ~~retries without limit~~ fixed in B11 (attempts
  that receive no data are limited by `maxRetries`; the HTTP retry wait honours Pause since B2).
  Still open: a fixed 5 s (HTTP) / 3 s (HLS/DASH) delay with no backoff, and the streaming retry wait
  is a plain `Thread.sleep`. Segment failures are never retried at task level (that code is commented out
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
- Test coverage: the fixes above added tests for `DownloadManager` (queue, video downloads, commit),
  `TaskInfoDB` / `.state` round-trips and the integration server's HTTP handling. Still untested:
  `AppDB` and the integration server's request routing. `AtomicIO` crash recovery is covered by
  `TestAtomicIO` (B9).

---

## 2. Incomplete or stubbed features

| # | Feature | State | Where | Suggested implementation |
|---|---------|-------|-------|--------------------------|
| F1 | **Scheduler** | The UI saves schedules and the ticker runs, but `triggerScheduledDownload` only logs, so scheduled downloads **never start** | `DownloadScheduler.kt:120-123` | Call `AppContext.downloader.resumeDownload(id)`, which now goes through `pumpQueue()` and respects the parallel limit (B5). Also catch up missed `ONE_TIME` entries at startup (fire if `epochMillis` has passed and it is within a grace window, otherwise drop), and remove entries on delete (B12). Consider a "stop at" time as well. |
| F2 | **Queues** | `QueueManager.attachToQueue` is empty, `QueueService.kt` is empty, and Start/Stop queue handlers are commented out | `Queues.kt`, `AppMenuHandler.kt:127-137, 338-348` | Model `DownloadQueue(id, name, downloadIds, maxParallel, schedule)`, persist it with `AtomicIO`, and extend `DownloadManager.pumpQueue()` (added in B5) to choose per queue. The scheduler can then start or stop whole queues. |
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
2. **Hangs and data loss:** B1, B2, B3, B4 and B9 are done. B8 (integration server responses) is
   also done.
3. **Queue and lifecycle:** B5 (single `pumpQueue()`), B6 and B7 are done. Then F1 + F2 on top of the
   pump, then B14.
4. **Resource hygiene:** B10, B11, B12 and B13 are done. Then B18.
5. **Polish:** B15 is done. Then B16–B21, remaining F-items, dead-code removal, CLAUDE.md update.
6. **Tests to add alongside:** `TaskInfoDB` / `AppDB` / `AtomicIO` round-trip and truncated-file
   recovery; `DownloadManager` queue limits using a fake `DownloaderTask`; integration server header
   format and the S1 origin/method rejection (403/405 cases); resuming encrypted HLS (extend `TestHlsE2E`); a 429 and
   stalled-read case in `MockHttpServer`.

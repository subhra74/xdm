# Browser download interception

How the browser extension decides to take a download away from Chrome, what it hands XDM,
and what happens when XDM cannot complete it. This supersedes the POST-sniffing heuristic
the extension grew to avoid hijacking form submissions.

Status: **implemented in the Chrome extension and xdm-app**; the Firefox extension still runs
the old model. The checks in §8 are still to be done against a real browser.

---

## 1. The invariant

> Only intercept a request the extension actually observed, as a GET, with its real headers
> captured. Everything needed to replay it is then already in hand, so the browser download
> is cancelled immediately — no stall, no pause, no confirmation round trip.

The corollary matters as much as the rule: **every gate that fails leaves the browser
download completely alone.** No `cancel`, no `erase`. Today a failed gate can still end with
a cancelled download that XDM cannot replay, which loses the file outright.

---

## 2. Current model (as-is)

| | |
|---|---|
| POST detection | `postUrls` map fed by `webRequest.onSendHeaders`, read by `wasPostRequest()` with a 60s TTL ([request-watcher.js:101](browser-extension/chrome-extension/request-watcher.js:101)) |
| Takeover decision | extension-local, on URL and file extension ([app.js:172](browser-extension/chrome-extension/app.js:172)) |
| Cancel | immediate, before XDM is told anything ([app.js:79](browser-extension/chrome-extension/app.js:79)) |
| Headers sent to XDM | synthesised — `User-Agent` from `navigator.userAgent`, `Referer` from `download.referrer` ([app.js:257](browser-extension/chrome-extension/app.js:257)) |
| Cookies | re-derived via `chrome.cookies.getAll({url})` |
| `/download` reply | the `ConfigDto` snapshot; carries no verdict |

### Defects this design closes

| | |
|---|---|
| **Cancel is destructive** | `onDeterminingFilename` cancels and erases the browser download before XDM has fetched anything. If XDM's replay 403s, the user has no file and no browser download to fall back on. |
| **POST detection fails open** | A miss means a POST-originated download is hijacked and re-requested as a GET, producing a broken file. The signal is negative and best-effort, so absence of evidence is treated as evidence of absence. |
| **`postUrls` leaks** | Entries evict only on a read that finds them stale; URLs never looked up accumulate for the life of the service worker. |
| **Cookies are reconstructed, not captured** | `chrome.cookies.getAll` matches by domain and path without the network stack's request context, so it over-includes cookies the browser would not have sent — SameSite=Lax/Strict on a cross-site download being the common case. XDM's replay then differs from what Chrome did. |
| **No host-permission gate** | `isMonitoringEnabled()` checks `appEnabled`, `userDisabled` and connector state, but never whether the extension can still see the network. Chrome's "On click" site-access setting withholds host permissions while `downloads` keeps firing, so the extension cancels downloads it has no way to replay. |
| **Dead config branches** | `ConfigDto.matchingHosts` is hardcoded `emptyList()` ([BrowserIntegration.kt:367](xdm-app/src/main/java/xdm/integration/BrowserIntegration.kt:367)), so the non-GET exception in `onSendHeadersEvent` and the host check in `isMatchingRequest` can never fire. `onMessage` never passes `requestFileExts` or `urlPatterns`, so those two branches are permanently empty too. |
| **~~Config changes never reached the extension~~** | *Fixed.* Nothing outside `CapturedVideoTracker` bumped the event channel, so config the extension gates on was published only on the next `/sync` — up to a watchdog interval later. `ignoreHost()` wrote `blockedHosts` and saved silently, by which time the user had retried the link and been captured again; `SettingsWindow.save()` did the same for `fileExtensions`, `videoExtensions` and `blockedHosts`. All three sites now call `EventChannel.notifyChanged()` after persisting. |

---

## 3. Target model

### The observed-request cache

Populated in `onHeadersReceivedEvent`, the one point where both request and response headers
are in hand. Keyed by URL, holding `{ method, requestHeaders, responseHeaders, cookie,
tabId, tabUrl, tabTitle, ts }` — the object `createRequestData` already builds.

- Keyed under both `res.url` and `req.url` when a redirect made them differ, because
  `DownloadItem` exposes `url` and `finalUrl` separately.
- Redirect hops share a `requestId` and re-fire `onSendHeaders`, so the entry naturally ends
  up holding the **final** hop. That is the right one — preserve it deliberately rather than
  by accident.
- Only download candidates are cached; `isMatchingRequest` already computes most of that
  judgement. Bounded by size cap plus TTL sweep, unlike `postUrls`.
- `method` is stored, not filtered at insert: the media path and the download path read the
  same cache with different method rules.

### The interception ladder

```
onDeterminingFilename(download, suggest)
  ├─ monitoring disabled ────────────► suggest(), return
  ├─ host permission withheld ───────► suggest(), return
  ├─ cache miss on finalUrl and url ─► suggest(), return
  ├─ cached method !== GET ──────────► suggest(), return
  ├─ !shouldTakeOver(...) ───────────► suggest(), return
  └─ take over ──────────────────────► suggest(); cancel(); erase(); post /download
```

`suggest()` is called exactly once on every path, as the API contract requires.

POST filtering is no longer a mechanism. It falls out of "only GET requests are ever
cached", replacing a negative heuristic with a positive one: the extension intercepts only
what it watched happen.

### Recovery

The user's escape hatch is the **Ignore address** link in XDM's download dialog, which adds
the host to `blockedHosts`. With the event-channel fix that reaches the extension
immediately, so a retried link is no longer captured and Chrome downloads it natively — for
that host, permanently.

---

## 4. Why cancel immediately

The alternative designs both exist to preserve the browser download until the outcome is
known. Neither earns its cost once §3 is in place.

| | Cancel immediately | Stall (defer `suggest`) | Pause, resume on ignore |
|---|---|---|---|
| Messages on the happy path | none | verdict round trip | cancel instruction |
| Protocol surface | `/download` stays fire-and-forget | verdict, correlation id, abandon endpoint, timeout default | command channel, correlation id, timeout default |
| Recovery from XDM failure | re-click the link | browser finishes the download | browser finishes the download |
| Recovery from user decline | blocklist, then re-click | same as cancel | resume in place, no re-click |
| Browser-side state held | none | a pending callback, ~3s | a paused connection, for the dialog's lifetime |

**The stall only ever addressed technical failure.** User decline is already handled by the
blocklist, which is a better answer because it is permanent — the user states an intent once
and never sees that host captured again.

**And technical failure is what §3 removes at the source.** The 403-on-replay case exists
because XDM was being handed a reconstructed request. Once it receives the genuine `Cookie`
header and the genuine request headers, for a GET the extension watched succeed, its replay
is materially the same request Chrome just made. Building a verdict protocol to catch the
residue of a problem that the cache fixes is the wrong order of work.

What is accepted: a download XDM takes but cannot complete is lost, and the user re-clicks.
When XDM fails repeatedly on a host, **Ignore address** is the escape.

---

## 5. What this fixes

- A failed handoff can no longer lose a file that the extension had no business taking.
- POST-originated downloads are excluded by construction rather than by a racing heuristic.
- XDM replays the request the browser actually made, with the cookies it actually sent.
- Withheld host permissions stop interception instead of silently breaking it.
- One bounded cache replaces a leaking map and three dead config branches.
- `chrome.cookies` leaves the interception path entirely; it remains only for the context
  menu, which acts on links the browser never requested.

---

## 6. Trade-offs

**Gained.** No destructive cancel. Faithful replay. Positive rather than negative
evidence for interception. No new protocol on the hot path — `/download` stays
fire-and-forget, `postMessage` stays synchronous-looking, no correlation ids, no timeouts,
no service-worker lifetime concerns.

**Paid.**

- *A failed XDM handoff costs a re-click.* No safety net. Mitigated by the failure becoming
  rare, and by the blocklist escape when it is not.
- *Cache misses are silent non-interception.* The new failure mode is XDM quietly not
  capturing, where the old one was capturing and breaking. Better, but needs logging to be
  diagnosable.
- *A GET-only rule may skip legitimate downloads* — service-worker-synthesised responses in
  particular. Correct, since XDM cannot re-fetch those either, but it is a real narrowing.

**Rejected alternatives.**

- *Stall via deferred `suggest()`.* Sound mechanism — `suggest` may be called
  asynchronously if the listener returns `true`, and the item will not complete until it is.
  Rejected because it only covers technical failure, which §3 removes, at the cost of a
  verdict protocol on every interception.
- *Pause, resume if the user clicks Ignore.* Technically viable: `DownloadItemImpl::Pause()`
  keeps the connection, and `Resume()` from `IN_PROGRESS_INTERNAL` calls `job_->Resume(true)`
  and returns without issuing any HTTP request, so it is not a ranged re-request.
  `CanResume()` for that state is unconditionally `IsPaused()`. It would also let the dialog
  express two distinct intents — close means "not this file", Ignore means "this file, but
  not through XDM". Rejected on cost, not correctness: the pause sits on the critical path
  for *every* interception, so every download needs a follow-up instruction (`cancel` on
  accept, `resume` on ignore) plus a timeout default, over a channel that does not exist —
  `ConfigDto` is a state snapshot, not a command queue. Worth revisiting once §3 has shipped;
  it changes only what happens after the gates pass.
- *Optional host permissions with a runtime grant.* Separate decision, recorded in §9.

---

## 7. Implementation

**Extension** (`browser-extension/chrome-extension/`)

- `request-watcher.js`: delete `postUrls`, `wasPostRequest()`, the POST-recording branch in
  `onSendHeadersEvent`, and the `matchingHosts` method exception. Add the observed-request
  cache, populated in `onHeadersReceivedEvent`, bounded and swept.
- `app.js`: drop both `wasPostRequest` calls; add the cache lookup and GET check to
  `onDeterminingFilename`; move every rejection path onto `suggest()`-and-return.
- `app.js`: `triggerDownload` takes the cached headers and cookie for the intercepted path.
  `chrome.cookies` stays only for `sendLinkToXDM` / `sendImageToXDM`.
- `app.js`: add a `permissions.contains({origins:["*://*/*"]})` gate to
  `isMonitoringEnabled()`, and update the icon from `permissions.onAdded` / `onRemoved`.
- `manifest.json`: drop `tabs`; `activeTab` plus the `tabId` already on webRequest events
  covers what it was used for.
- Log cache misses that would have been interceptions.

**xdm-app**

- ~~`EventChannel.notifyChanged()` in both `ignoreHost()` handlers and in `SettingsWindow.save()`.~~ Done.
- ~~Surface a visible error when a taken-over download fails to start.~~ Done: an auto-started
  browser download that fails before its first byte, with no progress window open, posts a tray
  notification (a message box when there is no tray) — `AppInstance.pendingHandoffs`.
- Optional, not done: drop the dead `matchingHosts` field from `ConfigDto`. The Chrome extension
  no longer reads it; the Firefox one still does (harmlessly, it is always empty).

---

## 8. Verify before shipping

1. **`onHeadersReceived` fires before `onDeterminingFilename`** for the same download.
   Logically necessary — Chrome needs the headers to classify it as a download — but the
   whole design rests on it.
2. **`suggest()` then `cancel()`, or the reverse.** The contract requires exactly one
   `suggest` call; which order is clean is undocumented. Watch for a race on small files
   where the download completes before `cancel` lands.
3. **Service-worker teardown between the two events.** They fire milliseconds apart on the
   same request, so the worker should be alive. `chrome.storage.session` is the insurance if
   misses appear.
4. **Cache hit rate** across real sites. A low rate means an ordering or eviction problem,
   not a site problem.

---

## 9. Notes

- **Host permissions stay broad and required.** Moving `*://*/*` to
  `optional_host_permissions` was considered and rejected: there is no viable reduced mode
  (the downloads API alone cannot replay a request, so a permission-less XDM would cancel
  downloads it cannot complete), enterprise deployment cannot pre-grant an optional
  permission, and the review-speed argument does not survive contact with evidence — uBlock
  Origin Lite ships required broad host permissions and reports sub-24-hour review turnaround.
  uBOL itself ran permissionless from launch until April 2025 and reverted for these reasons.
  What survived its revert, and what §3 adopts, is the discipline of reacting correctly to
  whatever host permissions are actually held.
- **POST is not captured for media either.** Removing it is a runtime no-op today, since
  `matchingHosts` is empty. It was never usable regardless: `createRequestData` captures no
  request body, so a POST media URL could not be replayed.
- **Chromium does not re-POST on resume.** `ResumeInterruptedDownload()` builds a fresh
  `DownloadUrlParameters(GetURL(), ...)` whose constructor defaults `method_` to `"GET"` and
  `post_id_` to `-1`, and never sets method, post body or post id — `DownloadItemImpl` holds
  no field for them. An interrupted POST download resumes as a ranged GET. Not load-bearing
  here, since only GETs are intercepted, but it is why that rule is worth keeping.

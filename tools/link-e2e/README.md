# Link handling: test instructions

How to check two behaviours of HTTP downloads, first in the Maven test suite and then end to end in the
real app against a local test server:

1. **Connection limit.** Some CDNs answer one connection too many with **403**, not 429, while the
   connections they admitted keep downloading. That is not an expired link. The download backs off
   (5 s, as after a 429), retries, and finishes. A 403 is still an expired link when no other segment
   of the download is getting data.
2. **Expired link refreshed from its source link.** When the browser was redirected from a site's
   download link to a short-lived signed file URL, the extension sends that first link as `sourceUrl`
   (and that host's cookies as `sourceCookie`). When the signed URL expires, XDM asks the source link
   again with only those cookies and checks the new URL is the same file (same size, same
   Last-Modified when known). It then swaps the URL in and resumes from the bytes on disk. A download
   refreshes again only after it made progress since its last refresh, and at most once every 30 s.
   Otherwise the usual "link expired" error is shown.

## 1. Automated tests (Maven)

These run with the rest of the suite and need nothing else: they use in-process test servers, not port 8597.

| What | Test |
|---|---|
| 403 beyond 3 connections: a new download and a paused-then-resumed one finish intact | `xdm-core` `TestHttpConnectionLimit` |
| A 403 with nothing else downloading is still an expired link | `xdm-core` `TestAuthHeaderResume` |
| The `/download` message's `sourceUrl`/`sourceCookie` reach the task info; dropped when blank or equal to `url` | `xdm-capture` `SourceLinkMessageTest` |
| The source link is saved with the download and read back | `xdm-core` `TestTaskInfoDB.http_roundTripKeepsSourceLink` |
| What a source link must answer (same size, same Last-Modified, not refused); only its own cookie sent; the progress and 30 s rule | `xdm-manager` `LinkRefresherTest` |
| Signed URL expires mid-transfer: the download fails, refreshes from `/link`, resumes on the new URL and finishes intact | `xdm-app` `DownloadManagerRefreshLinkTest.expiredLinkIsRefreshedFromItsSourceLinkAndResumes` |

Run just these:

```bash
mvn -q -pl xdm-core,xdm-capture,xdm-manager,xdm-app -am test -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest='TestHttpConnectionLimit,TestAuthHeaderResume,SourceLinkMessageTest,TestTaskInfoDB,LinkRefresherTest,DownloadManagerRefreshLinkTest'
```

Always build with `-am`: without it Maven takes the other modules from `~/.m2`, which may be another
branch's build.

## 2. End to end in the real app (manual, macOS and Linux)

`run.sh` starts `server.py`, starts the app jar with a throwaway profile, posts two downloads to the
integration port the way the extension does, and checks the results. It is not part of the Maven
suite: it needs the GUI app on port 8597 and opens its window on screen.

### Before you start

- **Quit XDM.** The test app needs port 8597. The script stops if something is already listening there.
- **Build the jar** from the code you want to test:

  ```bash
  mvn -q -pl xdm-app -am package -DskipTests
  ```

- **Your login entry.** A start that is not a profile's first run refreshes the login entry
  (`~/Library/LaunchAgents/app.xdm.autostart.plist`, or `~/.config/autostart/xdm-app.desktop`) so that it
  points at the jar being run. The script copies the entry first and puts it back on exit if it changed,
  even after a failure. If the script is killed with `kill -9`, restore it by hand from
  `login-item.bak` in the work folder it printed.
- `python3` and `curl` must be installed.

### Run it

```bash
tools/link-e2e/run.sh
```

It takes about half a minute. A passing run ends with:

```
1. connection limit (3 connections, 403 beyond)
  ok   limit.bin downloaded intact
  ok   the server refused extra connections
  ok   never more than 3 connections at once
  ok   busy refusals backed off
2. signed URL expiring after 2s, refreshed from its source link
  ok   signed file downloaded intact
  ok   the signed URL expired on the download
  ok   XDM asked the source link again
  ok   it resumed on the fresh link
  ok   only the source host cookie went to the link
PASS
```

The exit status is 0 on PASS and 1 otherwise. The work folder (profile, app log, server log, files)
is kept and its path printed: the app log is `data/logs/xdm-*.log`, the server's request log is `server.log`.

Settings, as environment variables:

| Variable | Default | |
|---|---|---|
| `XDM_JAR` | `xdm-app/target/xdm-app.jar` | the jar to test |
| `SERVER_PORT` | `18597` | the test server's port |
| `SIG_TTL` | `2` | seconds a signed URL lives. Keep it shorter than the download: about 3 s with 8 segments. |
| `TIMEOUT` | `120` | seconds to wait for each download |

### Doing the same by hand

To watch it in the app's window, or to try other cases:

```bash
python3 -I tools/link-e2e/server.py 18597 2
```

The first line prints the port, the file size and the SHA-256 every downloaded file must have. Then
start the app with its own profile. `MakeConfig.java` makes downloads start without the new-download
window; leave it out to confirm each one in the window instead.

```bash
P=$(mktemp -d); mkdir -p $P/data $P/out
java -cp xdm-app/target/xdm-app.jar tools/link-e2e/MakeConfig.java $P/data $P/out
java -Dxdm.data.dir=$P/data -jar xdm-app/target/xdm-app.jar
```

Post a download as the extension would. Connection limit:

```bash
curl -X POST http://127.0.0.1:8597/download -H "Content-Type: application/json" -d '{"url":"http://127.0.0.1:18597/limit.bin","filename":"limit.bin","fileSize":25165824,"requestHeaders":{"User-Agent":["Mozilla/5.0"]}}'
```

Signed link (get a fresh signature first, as the browser would, then send it with its source link):

```bash
U=$(curl -s -o /dev/null -w "%{redirect_url}" http://127.0.0.1:18597/link); curl -X POST http://127.0.0.1:8597/download -H "Content-Type: application/json" -d "{\"url\":\"$U\",\"cookie\":\"cdn=1\",\"sourceUrl\":\"http://127.0.0.1:18597/link\",\"sourceCookie\":\"site=1\",\"filename\":\"signed.bin\",\"fileSize\":25165824,\"requestHeaders\":{\"User-Agent\":[\"Mozilla/5.0\"]}}"
```

Check `curl http://127.0.0.1:18597/stats` and the app log:

- **Connection limit:** `server limit, backing off` lines, and the download finishes.
- **Signed link:** `link expired, asking its source link for a fresh one`, then
  `fresh link from its source, resuming`. The resumed segments' `RequestData` lines show `sig=2` with
  `downloaded` above 0, which means they continued rather than started over.

Other cases worth trying by hand:

- **No source link:** post the signed URL without `sourceUrl`. It must end as "link expired", and
  Refresh link offers to fix it.
- **A file that changed:** edit `SIZE` or `LAST_MODIFIED` in `server.py` while a signed download is
  paused, restart the server, then resume. The refresh must refuse the new URL and show "link expired".
- **A source link that keeps failing:** set `SIG_TTL` to 0.5 so every fresh URL expires before it
  delivers anything. After one refresh without progress the error must be shown, with no loop.
- **With the real extension:** a page whose download button redirects to the file (served from `/link`).
  The extension's `/download` message (in the app log, `INTEGRATION`) must carry the source link.

When you are done: quit the test app (or let `run.sh` do it) and start your own XDM again.

## Files

- `server.py`: the test server (standard library only). Endpoints: `/limit.bin`, `/link`,
  `/signed.bin`, `/stats`; see its header comment.
- `MakeConfig.java`: writes the throwaway profile's config through the app's own `AppConfig`.
- `run.sh`: the scripted end-to-end run above.

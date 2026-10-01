# Download origin marking (quarantine / Mark-of-the-Web)

Status: implemented (`utils/OriginMarker.kt`). Still to verify by hand: the Windows stream write and Gatekeeper with flags `0081`. All code lives in **xdm-app**; xdm-core is untouched.

## Problem

When the extension takes a download away from the browser, the browser never writes the file, so
nothing marks it as coming from the internet. Today an XDM download skips:

- **macOS**: Gatekeeper's first-open check (no `com.apple.quarantine` attribute).
- **Windows**: SmartScreen, Office Protected View, the Explorer "Unblock" checkbox, macro blocking,
  and Mark-of-the-Web (MOTW) propagation into extracted ZIPs (no `Zone.Identifier` stream).
- **Linux**: no security effect, but file managers show the origin URL that Chrome records.

## Decisions

- Every completed download (HTTP, HLS, DASH) is marked as coming from the internet, unless the
  user turns it off in XDM settings. There is no OS policy lookup and no zone mapping.
- **Windows**: write the `Zone.Identifier` alternate data stream, and nothing more.
  - No Attachment Services (COM): it loads antivirus DLLs into the XDM process, which is a crash risk.
  - No registry policy check.
  - No reopening the file after writing to look for an antivirus verdict.
- **macOS**: run `/usr/bin/xattr -w com.apple.quarantine <value> <file>`.
- **Linux**: Java's built-in attribute API (`UserDefinedFileAttributeView`).
- A failed mark is logged and never fails the download.

## Where it hooks in

`DownloadManager.onDownloadSuccess` (`xdm-app/.../DownloadManager.kt:189`). At that point the file
is already at `finalOutputFolder/finalFileName`: HTTP renames its in-folder temp file, and
streaming downloads rename `.<id>.xdm-part`. Marking runs on the calling downloader thread (not
the EDT), **before** the record flips to FINISHED, so the complete dialog, "Open" in the list, the
virus scan and the custom command all see a marked file.

```
onDownloadSuccess(event)
  activeSessions.remove(id)
  delete temp folder / .out
  if (config.markDownloadedFiles)
      AppContext.platform.markDownloadedFile(file, sourceOf(id))   // NEW, never throws
  appDB: status = FINISHED ...
  complete dialog / notification
  runPostDownloadActions(event)
  ...
```

`sourceOf(id)` reads the task info that is still in `taskInfoDB` at this point:

| Type | Source URL | Referrer |
|------|-----------|----------|
| HTTP | `HttpDownloadTaskInfo.url` | `origin` (referer or tab URL) |
| HLS  | `HlsDownloadTaskInfo.url` (manifest) | `origin` |
| DASH | `DashDownloadTaskInfo.url` | `origin` |

URL handling (a pure function with unit tests):
- Keep only http/https URLs.
- Remove the userinfo (`user:pass@`).
- Remove CR/LF so a URL can't add lines to the Zone.Identifier file.
- If no URL is usable, mark the file anyway, just without the URL lines.

## Components (all in xdm-app)

```
xdm-app/src/main/java/xdm/app/
  utils/OriginMarker.kt     // NEW: per-OS dispatch, URL cleaning, value formatting (≈90 lines)
  PlatformInvoke.kt         // + markDownloadedFile(file, source) -> OriginMarker
  DownloadManager.kt        // + sourceOf(id) and the call in onDownloadSuccess
  AppConfig.kt              // + markDownloadedFiles (appended after skipDuplicateManifests)
  ui/screens/settings/...   // + one checkbox next to "Run antivirus scan"
```

```kotlin
data class DownloadSource(val url: String?, val referrer: String?)

interface IPlatformInvoke {
    ...
    /** Blocking; never throws. Called off the EDT. Returns false if the mark could not be written. */
    fun markDownloadedFile(file: File, source: DownloadSource): Boolean
}
```

No native code and no new dependencies. Nothing touches `utils/win/` or the FFM layer.

## Windows: Zone.Identifier

Written to `<file>:Zone.Identifier` (UTF-8, CRLF line endings):
```
[ZoneTransfer]
ZoneId=3
ReferrerUrl=<referrer>
HostUrl=<url>
```
- `ReferrerUrl`/`HostUrl` lines are left out when there's no value.
- Written with `java.io.FileOutputStream("$path:Zone.Identifier")`. java.io accepts the `:`
  stream syntax, but NIO `Path` rejects it.
  **To verify on Windows before relying on it.** If java.io turns out not to accept it, the
  backup is writing the stream with `CreateFileW` over FFM. Only the write method changes; the
  design stays the same.
- Overwrites any existing stream, e.g. when replacing a file.
- FAT32/exFAT drives have no alternate data streams, so the write fails. That's logged at info
  level and the download is still a success.

Behaviour to accept:
- Intranet and trusted sites are also marked as internet (ZoneId 3), so they're treated more
  strictly than necessary.
- An admin's "Do not preserve zone information" policy is not honoured. XDM's own setting is the
  only switch.
- No antivirus verdict at save time. Real-time antivirus still scans the file as XDM writes it
  and when it's opened, and the existing "Run antivirus scan" option is unchanged.

## macOS: xattr

```
ProcessBuilder("/usr/bin/xattr", "-w", "com.apple.quarantine", value, file.absolutePath)
```
`value` = `0081;<unix seconds, lowercase hex>;XDM;`

**Verified on this machine (macOS 15.6.1):**
- `/usr/bin/xattr` is a native universal binary (x86_64 + arm64e), no longer a Python script.
- One call takes about 2 ms.
- The result shows up in `xattr -l` as `com.apple.quarantine: 0081;…;XDM;`.
- Arguments go straight to the program with no shell, so a file name containing `$`, `'` and
  spaces worked without escaping.
- Java's built-in attribute API **can't** be used: the JDK silently renames the attribute to
  `user.com.apple.quarantine`, which Gatekeeper ignores.

Details:
- Use the absolute path `/usr/bin/xattr`, so a different `xattr` earlier on the PATH can't be picked up.
- Wait for the process to finish, with a 10 s limit. Exit code ≠ 0 → log its stderr at info
  level and return false.
- No UUID. Chrome writes `0281;…;Chrome;<UUID>` (seen in `~/Downloads`) through the LaunchServices
  API, which also adds an entry to the quarantine events database. Gatekeeper only needs the
  attribute; we only lose the "downloaded from … on …" detail in the first-open prompt.
- **To verify in testing:** flag value `0081` must make Gatekeeper block or prompt for an unsigned
  `.app` inside a zip and for an unnotarised `.dmg`/`.pkg`, the same as Chrome's `0281`. If it
  doesn't, change the flags to `0281`.
- Archive Utility copies the attribute onto extracted files, as intended.
- The flag is not "Where from" metadata. Finder's "Where from" field
  (`com.apple.metadata:kMDItemWhereFroms`, a binary plist) is out of scope.

## Linux: user xattrs

`UserDefinedFileAttributeView`. The JDK adds the `user.` prefix, which is correct on Linux:
- `xdg.origin.url` = source URL
- `xdg.referrer.url` = referrer (only if present)

These match Chrome on Linux. If the filesystem has no user xattrs (FAT, some tmpfs/network
mounts), the write fails: logged at debug level, returns false.

## Settings and persistence

- `AppConfig.markDownloadedFiles: Boolean = true`, written **after** `skipDuplicateManifests` so
  older builds still read the config. A config without the field loads as `true`.
- Checkbox in the same settings panel as the antivirus options: "Mark downloaded files as coming
  from the internet (recommended)". Tooltip: on macOS, opening downloaded apps will go through
  Gatekeeper; on Windows, SmartScreen and Office Protected View apply.
- No per-download state, so nothing changes in `TaskInfoDB`, `AppDB` or `deleteMetadata`.

## Tests (xdm-app, JUnit 5)

- URL cleaning: userinfo removed, non-http dropped, CR/LF removed, null.
- Zone.Identifier text: all lines present; referrer or URL line left out when missing.
- macOS value format: flags, lowercase hex time, agent name.
- Config round-trip of `markDownloadedFiles`, and the default `true` when loading an older config.
- `onDownloadSuccess` with a fake `IPlatformInvoke`:
  - marking happens before the dialog and before `runPostDownloadActions`;
  - it's skipped when the setting is off;
  - a failed mark still finishes the download.
- Update the xdm-app test count in CLAUDE.md, and note the feature in
  `docs/ui-and-platform.md`.

Manual checks:
- **Windows:** `.exe` → SmartScreen prompt; `.docx` → Protected View; zip extracted in Explorer
  → contents marked; check the stream with `more < "file:Zone.Identifier"`; FAT32 USB drive → no
  error, download finishes.
- **macOS:** `xattr -l`; Gatekeeper on an unsigned `.app` zip and a `.dmg`.
- **Linux:** `getfattr -d`.

## Implementation order

1. `DownloadSource`, URL cleaning, `markDownloadedFiles` setting, the hook in `onDownloadSuccess`
   (with a no-op marker), and tests.
2. Windows Zone.Identifier.
3. macOS xattr.
4. Linux xattrs.

## Rejected alternatives

- **`IAttachmentExecute` (COM).** It honours policy and zone mapping and scans with the installed
  antivirus at save time, but it loads the antivirus vendor's DLL into the XDM process, where a
  faulty one can crash the JVM.
- **Registry policy check (`SaveZoneInformation`).** Not needed; XDM's own setting is the switch.
- **Reopening the file after writing to read an antivirus verdict (`ERROR_VIRUS_INFECTED`).**
  Dropped to keep this simple.
- **PowerShell `Set-Content -Stream`.** Breaks the no-powershell rule, adds a slow process start
  per download, fails on locked-down machines, and "java.exe starts powershell.exe to edit
  Zone.Identifier" is the kind of pattern security software watches for.
- **macOS `setxattr` over FFM.** Works (tested), but `xattr` does the same job with no native code.
- **macOS LaunchServices quarantine API (Objective-C runtime over FFM).** Only adds the quarantine
  events database entry; not worth the extra code.

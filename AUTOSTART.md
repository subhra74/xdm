# Start-on-login and JVM tuning

How XDM adds itself to and removes itself from OS startup, how the JVM tuning flags reach the process, what a
review of both turned up, and what was changed as a result.

Related: [APPCDS.md](APPCDS.md) (the AppCDS archive in detail), [docs/ui-and-platform.md](docs/ui-and-platform.md).

---

## 1. How XDM registers itself at login

**Code:** `xdm-app/.../utils/AutoStart.kt`, with the executable path worked out by `utils/AppLauncher.kt`.

Every platform uses a per-user login entry, so no admin rights are needed:

| OS | Add (`enable`) | Remove (`disable`) | "Is it on?" (`isEnabled`) |
|---|---|---|---|
| macOS | Writes the LaunchAgent `~/Library/LaunchAgents/app.xdm.autostart.plist` with `ProgramArguments` and `RunAtLoad=true` | Deletes the file | The file exists |
| Linux | Writes the XDG autostart file `~/.config/autostart/xdm-app.desktop` with `Exec=… --minimized` | Deletes the file | The file exists |
| Windows | Sets value `XDM` under `HKCU\Software\Microsoft\Windows\CurrentVersion\Run` through `Win32Registry` (`java.lang.foreign` → Advapi32) | Deletes that value | The value exists |

### The registered command

`AppLauncher.command()` plus `--minimized` (`MINIMIZED_FLAG`), so a login start goes straight to the tray without
opening a window. `AppLauncher.command()` resolves the executable in this order:

1. **Packaged build:** reads the `jpackage.app-path` property and prefers the `xdm-app` / `xdm-app.exe` launcher next to
   it. That second launcher is added with `jpackage --add-launcher` and has a stable name with no spaces, so the entry
   doesn't change if the display name ("Xtreme Download Manager") does.
2. **Native executable:** the current process's executable, unless its name starts with `java`.
3. **Dev fallback:** `<java> -jar <path to xdm-app.jar>`.

### When it runs

- **First run** (no config file yet), `AppContext.init`: enables autostart, but **only in a packaged build**
  (`AppLauncher.isPackaged`). The result is saved in `config.runOnStartup`.
- **Every later launch:** `AutoStart.sync()` compares the stored entry byte-for-byte with what `enable()` would write
  today and rewrites it if they differ. This adds `--minimized` to old entries and fixes the path after the app moves.
  It does nothing if autostart is off.
- **Settings toggle** (`AdvancedConfigPanel`): shows `AutoStart.isEnabled()`. On save it calls `setEnabled` only if the
  OS state differs from the toggle, then stores the state that actually took effect in `config.runOnStartup`.

The `xdm-app://` handler (`UrlScheme`) works the same way: it's registered per user, kept current by `sync()` on every
start, and launched through the same `AppLauncher` command, but without `--minimized`.

---

## 2. How the JVM tuning flags are applied

**The login entry doesn't carry any JVM flags.** It only starts the native launcher. The flags are built into that
launcher at packaging time:

1. `JAVA_OPTIONS` in `packaging/build-bundle.sh` (the same list is `$JavaOptions` in `build-bundle.ps1`) sets:
   - SerialGC with low heap free ratios and `-Xms4m`, so the heap goes back to the OS quickly. `-Xmx` is deliberately
     left out.
   - Metaspace ratios, `CompressedClassSpaceSize=64m` and `ClassUnloading`.
   - Tiered JIT with one C1 and one C2 thread (`CICompilerCount=2`), a 32 MB code cache, and the compiler directive
     `packaging/jit-directives.json`: C1 for all code, C2 only for the JDK's crypto methods (PACKAGING.md §3.2).
   - `SoftRefLRUPolicyMSPerMB=0`, `-UsePerfData` and `jdk.nio.maxCachedBufferSize`.
   - Depending on JDK version: `--enable-native-access=ALL-UNNAMED` (22+) and `UseCompactObjectHeaders` (25+).
   - When a class list exists, the AppCDS flags described in §4.
2. Each option is passed to jpackage as `--java-options`. jpackage writes them into each launcher's
   `app/<launcher>.cfg` (`Contents/app/` on macOS), in the `[JavaOptions]` section, and the native launcher reads that
   file every time it starts the JVM. `$APPDIR` in a value is expanded to the image's app directory.
3. The launcher added with `--add-launcher xdm-app` gets the same java-options. So a start from the login entry, the
   `xdm-app://` handler or the Start menu all run with the same flags.

A dev run (`java -jar`) gets none of these flags. Tuning only exists in jpackage builds.

---

## 3. Review findings

| # | Finding | Status |
|---|---|---|
| 1 | The settings toggle showed `config.runOnStartup`, not the OS state. A failed enable still saved `true`, and an entry removed from outside XDM left the toggle ON (switching it ON again did nothing). `setEnabled` also reported success when `enable()` gave up because it couldn't resolve the executable. | **Fixed** |
| 2 | Disabling XDM in Windows Task Manager only writes to `…\Explorer\StartupApproved\Run`, so `isEnabled()` still reports autostart as on. | Open |
| 3 | A first run from `java -jar` registered `<java> -jar …/target/xdm-app.jar --minimized` as a real login item. | **Fixed** |
| 4 | Dev and fallback launches get no JVM tuning. | Accepted: tuning only applies to jpackage builds |
| 5 | Nothing removes the login entry on uninstall. | Dropped: jpackage is only used to build the app image |
| 6 | The AppCDS flags in APPCDS.md were not in either build script, and its macOS note named `Info.plist` / `$APP_ROOT` instead of `app/*.cfg` / `$APPDIR`. | **Fixed** |
| 7 | The Linux `Exec=` line only quoted arguments with spaces; the Desktop Entry spec also requires escaping `"` `` ` `` `$` `\` and writing `%` as `%%`. | **Fixed** (the `xdm-app://` handler had the same bug and is fixed too) |
| 8 | On macOS 13+, a hand-written LaunchAgent triggers the "Background Items Added" notice. `SMAppService` is the modern API but needs a signed bundle. | Open: the current approach is reasonable |
| 9 | `-XX:MetaspaceReclaimPolicy=aggressive` was removed in JDK 21. The JVM ignores it and prints a warning on every start. | Open: safe to delete from both scripts |
| 10 | Dev runs still register the `xdm-app://` handler against `java -jar` (same kind of problem as #3). | Open: left alone so the handler can still be tested from an IDE |

---

## 4. Changes made

### Settings toggle (#1)
- `AdvancedConfigPanel.load()` sets the toggle from `AutoStart.isEnabled()`.
- `AdvancedConfigPanel.save()` changes the entry only if the OS state differs, then stores `AutoStart.isEnabled()`.
- `AutoStart.setEnabled()` returns `isEnabled() == enabled` instead of always `true`.

### No login entry from dev runs (#3)
- New `AppLauncher.isPackaged`: true when `jpackage.app-path` is set.
- The first-run enable in `AppContext.init` only happens when `isPackaged` is true; otherwise it logs and skips.

### Linux `Exec=` escaping (#7)
- New `AppLauncher.desktopExec(cmd)`. An argument that contains a reserved character is double-quoted, with `"`,
  `` ` ``, `$` and `\` escaped inside the quotes. Every backslash is then doubled (the string-level escape), and `%`
  becomes `%%`.
- `AutoStart` and `UrlScheme` both use it. Existing entries are rewritten on the next start through `sync()`.

### AppCDS in the build (#6)
Both `build-bundle.sh` and `build-bundle.ps1` now:
1. Add the version-gated flags to the option list before the runtime is first used, so the archive is built with
   exactly the flags the launcher will use.
2. **Record** (only with `--record-classes` / `-RecordClasses`): run XDM on the bundled runtime with
   `-XX:DumpLoadedClassList`, writing `packaging/cds/<os>.classlist` (`mac`, `linux`, `windows`).
3. **Pin** the bundled jar's mtime to 2020-01-01T00:00:00Z (epoch `1577836800`).
4. **Dump** `xdm.jsa` into the jpackage input when the class list exists.
5. **Add launcher flags:** `-XX:SharedArchiveFile=$APPDIR/xdm.jsa`,
   `-XX:+UnlockDiagnosticVMOptions -XX:ArchiveRelocationMode=0` and `-Dxdm.cds.mtime=1577836800`.
6. **Re-pin** the jar inside an `app-image`, because jpackage gives the copied jar a fresh mtime and that alone disables
   the archive.

Without a class list, the bundle is built as before, with no archive.

**App side:** the new `utils/CdsJarPin.kt` runs first thing in `AppMain`. If the jar's mtime no longer matches
`xdm.cds.mtime` (after an installer or copy rewrote it), it restores it. The start that finds the jar changed runs
without the archive; later starts use it. The install directory must be writable by the user for this to work.

Not wired in: `-XX:AllocateHeapAt` (its directory has to exist before the JVM starts) and the compiler directives from
APPCDS.md §6.

### Docs
- APPCDS.md: macOS note corrected, and a new §11 describing the build flow.
- docs/ui-and-platform.md: first-run gating and the settings toggle behaviour.

### Tests
`AppLauncherTest` has 3 new tests (`desktopExec` escaping and `isPackaged`) and passes (9 tests).

---

## 5. Verification

- **Unit tests:** `AppLauncherTest` passes (9 tests).
- **macOS/aarch64, JDK 25:** built an app-image with a throwaway class list, then replayed the `.cfg` flags through the
  image's `java` with `-Xshare:on -Xlog:cds`:
  - with the pinned jar, the archive loads (`Mapped static region`);
  - after moving the `.app`, it still loads;
  - after touching the jar, it's rejected with "timestamp has changed", which is the case `CdsJarPin` fixes.
- **Not checked:**
  - That the real launcher expands `$APPDIR`. Checking it meant starting the app on the desktop. The launcher already
    uses `$APPDIR` for `app.classpath` in the same file, so it's expected to work.
  - `build-bundle.ps1`. It has not been run (`pwsh` isn't available on this machine).

---

## 6. To do

1. **Record a class list on each OS and commit it.** No class list is checked in yet, so builds still come out without
   an archive. Quit any running XDM first, run a real download to completion, then quit from the tray:
   ```bash
   packaging/build-bundle.sh --record-classes
   ```
   On Windows: `packaging\build-bundle.ps1 -RecordClasses`.
2. Run `build-bundle.ps1` on Windows once to check the new AppCDS section.
3. Delete `-XX:MetaspaceReclaimPolicy=aggressive` from both scripts (#9).
4. The "xdm-app 98" test count in CLAUDE.md is out of date because of other uncommitted test changes; update it once
   those land.

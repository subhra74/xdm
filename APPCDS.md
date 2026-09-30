# AppCDS + JVM tuning for XDM

How to build a class-data-sharing archive for the shipped bundle and which JVM flags to
run it with. Everything here is measurement-backed; the raw numbers and the dead ends are
in the tuning notes this file condenses (`jvm-gc-tuning.md`, kept outside the repo).

> **Conscrypt has been removed.** XDM now uses the JDK's own TLS (SunJSSE), and the packaged
> launcher runs C1 for all code with C2 only for the JDK's crypto hot methods
> (`packaging/jit-directives.json`, see PACKAGING.md §3.2). The launcher flags and §6 below
> are updated for that; the memory and CPU tables were measured with Conscrypt and have not
> been re-measured. Tiered compilation with the directive is required; there is no C1-only mode.

## Intent

The goal is to make XDM actually use less memory while it sits in the tray — it is a
background app that users leave running for days, next to a browser that already wants
several GB.

XDM's Java **heap** is the small part (~11 MB live), so `-Xmx` is beside the point. The real
cost is elsewhere: ~21 MB of class metadata (~4,850 classes at idle), the JIT's code cache
and compiler threads, GC bookkeeping sized off the *maximum* heap, and native AWT/TLS
allocations. This document attacks those.

Two of the flags below need an honest caveat, because they are easy to mistake for a real
win when you are watching Task Manager:

* **AppCDS is a genuine reduction.** Class metadata is mapped read-only from the archive
  instead of being parsed into per-process memory, so the JVM allocates less and — if two
  JVMs on the machine use the same archive — pays for it once. Metaspace shrinks for real.
* **`-XX:AllocateHeapAt` mostly moves the heap from private to file-backed pages.** Task
  Manager's "Memory" column drops ~18 MB because it counts only private pages, but total
  working set barely changes. It is not a lie — those pages can be evicted to that file
  under pressure instead of the pagefile — but if you are optimising real RAM rather than
  the number a user sees, this flag is optional. Same for `ArchiveRelocationMode=0`: it
  keeps archive pages shared instead of privatising them on relocation (a real saving, at a
  small ASLR cost).

Everything else — Serial GC, the bounded code cache, `-XX:-UsePerfData` — reduces what the
process allocates.

Measured with the retired Conscrypt setup, which ran C1 only; the current directive (§6) keeps
C2 for crypto and has not been re-measured here:

| Setup (Windows 11, private working set, idle) | MB |
|---|---|
| stock JVM | 82–113 |
| tuned flags only (Serial GC, bounded JIT, no perf data) | 76–78 |
| \+ heap file \+ `ArchiveRelocationMode=0` | ~49 |
| **\+ static AppCDS** | **28–29** |

Private working set is the metric because it is what users complain about. Where a row
reflects reclassification rather than a real reduction, the notes say so.

The archive is generated **at build time and shipped**. Never generate it on the user's
machine as part of startup.

---

## 1. Prerequisites

* JDK 25 (Liberica/Microsoft/Corretto are equivalent once tuned; Liberica still lists
  Windows 7 SP1+). The archive is tied to the **exact JDK build** — regenerate it whenever
  the bundled runtime changes.
* A built fat jar: `mvn -q clean package` → `xdm-app/target/xdm-app.jar`.
* The same jar layout you will ship. The archive records the classpath, so build it against
  the *bundled* jar (the one `packaging/build-bundle.*` trims foreign natives from), not the
  untrimmed reactor output.

---

## 2. Build the runtime

`packaging/build-bundle.sh` / `.ps1` already do the `jlink` + `jpackage` work. If you are
assembling the low-memory layout by hand, link with **uncompressed** modules — compressed
modules get inflated into private memory at runtime, which is exactly what we are trying to
avoid:

```bat
jlink --add-modules java.base,java.desktop,java.logging,jdk.unsupported,jdk.crypto.ec,jdk.crypto.mscapi,jdk.localedata,jdk.accessibility ^
  --strip-debug --no-header-files --no-man-pages --compress=zip-0 --output runtime
```

Resulting app folder:

```
app\xdm-app.jar
app\jit-directives.json                      # see §6
app\xdm.jsa                                  # produced in §4
```

---

## 3. Record the class list

Start the app with the **final runtime flags** and use it the way users will — in
particular **run a real download to completion**, then close the app. The list is written
as classes load, so an idle-only recording misses the download path (measured: idle-recorded
archive = 31.4 MB during downloads, download-recorded = 28–30 MB).

```bat
runtime\bin\java.exe -XX:DumpLoadedClassList=classes.lst ^
  -XX:+UseSerialGC -Xms4m -XX:MinHeapFreeRatio=5 -XX:MaxHeapFreeRatio=10 -XX:-UsePerfData ^
  -XX:AllocateHeapAt=%LOCALAPPDATA%\xdm-lowmem\heap ^
  -XX:+UnlockDiagnosticVMOptions -XX:ArchiveRelocationMode=0 ^
  -XX:CICompilerCount=2 -XX:ReservedCodeCacheSize=32m ^
  -XX:CompilerDirectivesFile=app\jit-directives.json ^
  -jar app\xdm-app.jar
```

`%LOCALAPPDATA%\xdm-lowmem\heap` must exist first — `AllocateHeapAt` does not create it.

---

## 4. Dump the static archive

One file holding JDK **and** app classes, which replaces the runtime's own base archives:

```bat
runtime\bin\java.exe -Xshare:dump -XX:+UseSerialGC ^
  -XX:SharedClassListFile=classes.lst ^
  -XX:SharedArchiveFile=app\xdm.jsa -cp app\xdm-app.jar

del runtime\bin\server\classes.jsa runtime\bin\server\classes_nocoops.jsa
```

Static vs dynamic — static wins for shipping (one self-contained 38 MB file, no base
archive to keep in sync, slightly lower peak):

| | During/after download | Peak | On disk |
|---|---|---|---|
| **static** | **28.2 / 29.0 MB** | 29.8–30.2 | 38.1 MB |
| dynamic | 28.2 / 29.8 MB | 30.8–31.1 | 22.8 MB + 13.9 MB base `classes.jsa` |

The dynamic route, if you want it (no class list, dump from a live process):

```bat
runtime\bin\java.exe -XX:+RecordDynamicDumpInfo <runtime flags> -jar app\xdm-app.jar
jcmd <pid> VM.cds dynamic_dump app\xdm.jsa
```

---

## 5. Pin the jar timestamp

**The archive validates the classpath by exact jar size + mtime.** If either changes, the
JVM under the default `-Xshare:auto` *silently* runs without the archive — ~47 MB instead
of ~28, with no warning. Zip extraction and installers routinely rewrite mtimes, so pin it
at build time **and again in the launcher before every start**:

```powershell
(Get-Item app\xdm-app.jar).LastWriteTimeUtc = [datetime]::new(2026,9,22,0,0,0,[DateTimeKind]::Utc)
```

Moving the install folder is fine — only the jar identity matters, not its path.

---

## 6. Compiler directive: C1 for all code, C2 for crypto

XDM uses the JDK's TLS, whose AES-NI / ARMv8 crypto intrinsics only C2 applies. Tiered
compilation with the directive `packaging/jit-directives.json` (copied to
`app\jit-directives.json`; see PACKAGING.md §3.2) is therefore required on every target:

1. **C1 and C2 for the crypto hot methods** (AES, GHASH, CBC, ChaCha20, Poly1305, SHA and the
   SHA callers).
2. **C1 for everything else** (`*.*`, C2 excluded).

The first matching rule wins, and the explicit `"Exclude": false` on the allow rule is required.
Verified on Apple M2 / JDK 25 with `-XX:+PrintCompilation` over six cipher suites: C1 compiled
code in 39 packages, and every method that reached tier 4 (31) was on the list; 309 others
the JVM wanted on C2 were refused.

The same file is used on every target (Windows, macOS, Linux; x64 and arm64): its method
names are the JDK's, not a platform's.

An earlier variant compiled only the download path with C1 and left the rest interpreted
(measured with Conscrypt: ~3 MB less at idle). It was dropped: with the JDK's TLS the whole
JDK must be on C1 for the intrinsics to pay off, which removes most of its saving, and one
directive is easier to keep correct across JDK updates.

---

## 7. Run

Launcher (`xdm.cmd`): create `%LOCALAPPDATA%\xdm-lowmem\heap`, re-pin the jar mtime (§5),
then:

```bat
start "" runtime\bin\javaw.exe ^
  -XX:+UseSerialGC -Xms4m -XX:MinHeapFreeRatio=5 -XX:MaxHeapFreeRatio=10 -XX:-UsePerfData ^
  "-XX:AllocateHeapAt=%LOCALAPPDATA%\xdm-lowmem\heap" ^
  -XX:+UnlockDiagnosticVMOptions -XX:ArchiveRelocationMode=0 ^
  "-XX:SharedArchiveFile=%~dp0app\xdm.jsa" ^
  -XX:CICompilerCount=2 -XX:ReservedCodeCacheSize=32m ^
  "-XX:CompilerDirectivesFile=%~dp0app\jit-directives.json" ^
  -jar "%~dp0app\xdm-app.jar"
```

What each flag is for:

| Flag | Why |
|---|---|
| `-XX:+UseSerialGC -Xms4m -XX:MinHeapFreeRatio=5 -XX:MaxHeapFreeRatio=10` | Lowest native GC overhead and gives the heap back quickly. G1/Parallel size card tables and remembered sets from the *maximum* heap, and we deliberately set no `-Xmx`. `AppMain`'s periodic `System.gc()` is what triggers the shrink (`--no-gc` disables that thread). |
| `-XX:AllocateHeapAt=<dir>` | Heap becomes a file-backed mapping → shared, not private (−18 MB). Temp, delete-on-close file: no disk space used, ~5 KB/s at idle. Failure mode is *disk full → crash*, not OOM. Directory must exist. |
| `-XX:+UnlockDiagnosticVMOptions -XX:ArchiveRelocationMode=0` | Maps the CDS archive at its preferred address instead of relocating it (relocated pages turn private): −9 MB. Costs a little ASLR hardening. |
| `-XX:SharedArchiveFile=…` | The archive from §4. |
| `-XX:CICompilerCount=2 -XX:ReservedCodeCacheSize=32m` | Tiered with one C1 and one C2 thread, bounded code cache. C2 only runs for the directive's crypto methods (§6). |
| `-XX:-UsePerfData` | No `hsperfdata` mmap file. |

Deliberately **not** used: `-Xmx` (no benefit, and caps a download manager needlessly),
`-XX:MaxDirectMemorySize`, `-Xss` (OOM / StackOverflow risk). Metaspace flags do nothing —
XDM's classes live in the app loader and are never unloaded, so only CDS shrinks metaspace.

---

## 8. macOS and Linux equivalents

The whole recipe applies; only paths, the native library name, and the launcher shell
change. Verified on macOS/aarch64 with JDK 25: both `-XX:AllocateHeapAt` and
`-XX:ArchiveRelocationMode=0` are accepted and the archive maps at its preferred address
(`CDS heap data relocation delta = 0 bytes`).

| | Windows | macOS | Linux |
|---|---|---|---|
| jlink modules | + `jdk.crypto.mscapi` | base list | base list |
| heap-file dir | `%LOCALAPPDATA%\xdm-lowmem\heap` | `~/Library/Caches/xdm-lowmem/heap` | `${XDG_CACHE_HOME:-~/.cache}/xdm-lowmem/heap` |
| launcher binary | `javaw.exe` | `java` | `java` |
| jpackage types | `app-image`, `msi`, `exe` | `app-image`, `dmg`, `pkg` | `app-image`, `deb`, `rpm` |
| line continuation | `^` | `\` | `\` |

Record the class list and dump the archive exactly as in §3–§4, with `runtime/bin/java`:

```bash
HEAP="${XDG_CACHE_HOME:-$HOME/.cache}/xdm-lowmem/heap"   # macOS: ~/Library/Caches/xdm-lowmem/heap
mkdir -p "$HEAP"

runtime/bin/java -XX:DumpLoadedClassList=classes.lst \
  -XX:+UseSerialGC -Xms4m -XX:MinHeapFreeRatio=5 -XX:MaxHeapFreeRatio=10 -XX:-UsePerfData \
  "-XX:AllocateHeapAt=$HEAP" \
  -XX:+UnlockDiagnosticVMOptions -XX:ArchiveRelocationMode=0 \
  -XX:CICompilerCount=2 -XX:ReservedCodeCacheSize=32m \
  -XX:CompilerDirectivesFile=app/jit-directives.json \
  -jar app/xdm-app.jar      # then run a real download and quit

runtime/bin/java -Xshare:dump -XX:+UseSerialGC \
  -XX:SharedClassListFile=classes.lst \
  -XX:SharedArchiveFile=app/xdm.jsa -cp app/xdm-app.jar

rm -f runtime/lib/server/classes.jsa runtime/lib/server/classes_nocoops.jsa
```

Note the base-archive path: `lib/server/` on macOS and Linux, `bin/server/` on Windows.

Run:

```bash
#!/bin/sh
# xdm.sh — beside runtime/ and app/
here=$(cd "$(dirname "$0")" && pwd)
heap="${XDG_CACHE_HOME:-$HOME/.cache}/xdm-lowmem/heap"
mkdir -p "$heap"
touch -t 202609220000 "$here/app/xdm-app.jar"     # pin the jar mtime, see §5

exec "$here/runtime/bin/java" \
  -XX:+UseSerialGC -Xms4m -XX:MinHeapFreeRatio=5 -XX:MaxHeapFreeRatio=10 -XX:-UsePerfData \
  "-XX:AllocateHeapAt=$heap" \
  -XX:+UnlockDiagnosticVMOptions -XX:ArchiveRelocationMode=0 \
  "-XX:SharedArchiveFile=$here/app/xdm.jsa" \
  -XX:CICompilerCount=2 -XX:ReservedCodeCacheSize=32m \
  "-XX:CompilerDirectivesFile=$here/app/jit-directives.json" \
  -jar "$here/app/xdm-app.jar" "$@"
```

Platform notes:

* **Timestamp pinning matters more here**, not less: `tar`, `unzip` and `cp` all rewrite
  mtimes by default. Use `tar -p`/`rsync -t` when packaging, and re-`touch` in the launcher
  as above. macOS `touch -t` takes `YYYYMMDDhhmm`; GNU `touch -d '2026-09-22 00:00:00 UTC'`
  is equivalent.
* **jpackage bundles** don't run a shell script — `--java-options` are baked into each
  launcher's `app/<launcher>.cfg` (`Contents/app/` on macOS), and the launcher expands
  `$APPDIR` there to the image's app directory. See §11 for what the build scripts do.
  Code-signing and notarisation do **not** touch the jar's mtime, but rebuilding the jar does.
* **macOS measurement**: Activity Monitor's "Memory" column is *phys\_footprint*, which
  counts dirty + compressed pages and, unlike Windows' private working set, does **not**
  exclude clean file-backed pages the process is using. So the AppCDS saving shows up, the
  `AllocateHeapAt` reclassification largely does not. Read it precisely with
  `footprint -p <pid>` or `vmmap --summary <pid>`.
* **Linux measurement**: `ps -o rss= -p <pid>` counts shared pages in full; use
  `grep -E 'Rss|Pss|Private' /proc/<pid>/smaps_rollup` and compare **Pss**, which charges
  shared pages proportionally — that is the closest thing to a fair number.
* **Linux GUI trimming**: there is no equivalent of Windows trimming a minimised window's
  working set, so idle numbers are steadier but nominally higher than the Windows figures in
  this document. Compare within a platform only.
* The same directive works on every target: its method names are the JDK's, not a
  platform's. Checked on macOS arm64 (§6); re-check with `-XX:+PrintCompilation` after a JDK
  update, since a renamed method silently stays on C1.
* `jdk.crypto.mscapi` in the §2 module list is Windows-only; drop it elsewhere. The base
  list in `packaging/build-bundle.sh` (`java.desktop,java.logging,jdk.crypto.ec,jdk.unsupported`)
  is the portable one.

## 9. Verify

On a target machine, add:

```bat
-Xshare:on -Xlog:cds=info
```

`-Xshare:on` makes the JVM **refuse to start** if the archive cannot be mapped, instead of
silently falling back. Look for `Mapped static region` in the log (plus
`Mapped dynamic region` if you shipped the dynamic archive).

Useful diagnostics:

```bat
jcmd <pid> VM.metaspace
jcmd <pid> VM.native_memory summary scale=MB     :: needs -XX:NativeMemoryTracking=summary
jcmd <pid> GC.heap_info
java -Xlog:class+load=info:file=classload.log …  :: which classes load, and from where
```

Measuring: on Windows read the **Memory** column (private working set) with the window
*normal*, not minimised — Windows trims minimised windows. On macOS use `footprint -p <pid>`,
on Linux **Pss** from `/proc/<pid>/smaps_rollup` (see §8). Never measure two JVMs at once,
and treat ±1–2 MB as noise. `jcmd <pid> VM.metaspace` is the check that AppCDS did something
real: it should fall from ~21 MB committed to a few MB.

---

## 10. Rules and caveats

* **Regenerate `xdm.jsa` whenever the jar or the bundled runtime changes.** A stale archive
  is ignored silently and costs ~19 MB.
* The archive is **JIT-mode independent** — it holds class metadata, not compiled code, so
  the same archive serves the directive and the "Full JIT" override.
* **No CPU-feature dependency** — any x64 or aarch64 machine of the archive's own
  architecture works (an archive is per JDK build, so per platform anyway). If the preferred
  address cannot be reserved, the archive is relocated: still correct, smaller saving.
* The archive assumes **compressed oops**. On machines with 128 GB+ RAM the default heap
  exceeds 32 GB, compressed oops turn off, and the archive is skipped.
* If you would rather not ship an archive at all, `-XX:+AutoCreateSharedArchive
  -XX:SharedArchiveFile=<per-user cache dir>/xdm.jsa` self-heals on version changes, but the
  first run pays the dump cost and gets no saving.

---

## 11. What `packaging/build-bundle.*` does

The scripts automate §3–§5 and §7 for the jpackage image, with the class list as the one
manual input:

1. **Record** (only with `--record-classes` / `-RecordClasses`): runs XDM on the freshly
   linked runtime with the final flags and `-XX:DumpLoadedClassList`, writing
   `packaging/cds/<os>.classlist` (`mac`, `linux`, `windows`). Quit any running XDM first,
   run a real download to completion, then quit from the tray. Check the list in, and
   re-record it when the app's startup or download path changes a lot. A stale list is not
   fatal: missing classes are skipped at dump time.
2. **Pin** the bundled jar's mtime to 2020-01-01T00:00:00Z.
3. **Dump** `xdm.jsa` into the jpackage input with the launcher's own flags. It must use the
   same flags: `UseCompactObjectHeaders` or a different collector would make the JVM reject
   the archive.
4. **Launcher flags**: `-XX:SharedArchiveFile=$APPDIR/xdm.jsa`,
   `-XX:+UnlockDiagnosticVMOptions -XX:ArchiveRelocationMode=0`, and
   `-Dxdm.cds.mtime=1577836800`.
5. **Re-pin** the jar inside an `app-image`, because jpackage's copy gets a fresh mtime.

Without a class list, the bundle is built as before, with no archive.

The launcher cannot run code before the JVM starts, so §5's "re-pin before every start" is
done by the app instead: `CdsJarPin` (called first thing in `AppMain`) compares the jar's
mtime with `xdm.cds.mtime` and restores it. The start that finds the jar drifted runs without
the archive; every start after that uses it. This covers installers and copies that rewrite
mtimes, as long as the install directory is writable by the user.

Not wired in (deliberately, for now): `-XX:AllocateHeapAt`, whose directory has to exist
before the JVM starts, and the §6 compiler directives. The JDK's base `classes.jsa` is not
deleted; jlink does not generate one unless asked.

Verified on macOS/aarch64, JDK 25: the image's archive maps (`Mapped static region`), still
maps after the `.app` is moved, and is rejected with "timestamp has changed" once the jar is
touched.

# CLAUDE.md

XDM (Xtreme Download Manager): desktop download manager. Maven multi-module Kotlin/JVM project with a Swing UI (FlatLaf),
driven by a browser extension that forwards downloads/media to the running app over a local HTTP server (`127.0.0.1:8597`).

Modules: `xdm-core` (download engine, no UI deps, JDK 8), `xdm-app` (Swing app + browser integration, JDK 25),
`hls-muxer` (old experiment, not wired in).

## Detail docs (read when relevant)
- [docs/build.md](docs/build.md) — build/test commands, toolchain, version pins, runtime layout
- [docs/architecture.md](docs/architecture.md) — download flow, persistence, task info, manifests, muxing
- [docs/ui-and-platform.md](docs/ui-and-platform.md) — Swing UI, icons, AutoStart/UrlScheme/KeepAwake, Win32 layer
- [PACKAGING.md](PACKAGING.md) — installers (WiX MSI, dmg, nfpm), XDM 8 migration, updates, full-JIT `.cfg` override
- Other: [SCHEDULER.md](SCHEDULER.md), [FILE_PLACEMENT.md](FILE_PLACEMENT.md), [APPCDS.md](APPCDS.md)

## Essentials
```bash
mvn -q -pl xdm-app -am package                                  # build (fat jar: xdm-app/target/xdm-app.jar)
mvn -q -pl xdm-core test -DskipTests=false -Dtest=SomeTest      # scoped test run
java -jar xdm-app/target/xdm-app.jar                            # run (main: xdm.app.AppMain)
```
- JDK 25 is picked via a Maven toolchain (`~/.m2/toolchains.xml`, see `packaging/toolchains.sample.xml`), not `JAVA_HOME`.
- Keep Kotlin >= 2.3.21 and Surefire pinned at 3.5.6 (check test counts: xdm-core 197, xdm-app 103).
- Tests are JUnit 5; assertion message goes **last**. Kotlin sources live in `src/main/java`.
- State lives in `~/.xdm-app/`.

## Conventions
- Reach shared services through `AppContext` (service locator), not by passing them around.
- xdm-core must not depend on Swing/app types; talk to the app via `DownloadHost`/`FileProvider` and task-info models.
- Changing a persisted `*DownloadTaskInfo` field: update the `TaskInfoDB` read/write pair (and `.state`) together, same order.
- Prefer `AtomicIO` for new on-disk state. Any new per-download file must be added to `DownloadManager.deleteMetadata`.
- All UI mutation on the EDT. Every public `IAppInstance` method may be called from any thread, so it must marshal itself
  (`runOnUIThread`/`invokeLater`); pass download ids to the EDT and resolve the row via `db.indexById` there.
- `AppDB` accessors sync on the `AppDB` instance. `DownloadManager` lock order: `queue` → `appDB`; never take `queue` while holding `appDB`.
- **Each download gets its own `HttpClientImpl`** (own OkHttp `Dispatcher`/`ConnectionPool`, closed when the download ends).
  Deliberate: never propose a shared OkHttp client or connection pool. Reduce per-client cost instead (e.g. the shared `SSLContext`).
- Streaming downloads are muxed by the pure-Kotlin `TransmuxingMuxer` (no ffmpeg).
- Windows integration uses `java.lang.foreign` (`utils/win/`); never reintroduce `reg.exe`/`.reg`/`powershell.exe` helpers.

# UI & platform integration

## UI
Swing UI under `xdm-app/.../ui`: `screens/` (`AppWindow` is the main window) and `components/` (`MainListView`/
`MainListViewModel`, toolbar, filters, progress widgets). `AppInstance` (implements `IAppInstance`) is the facade for
showing/updating windows and marshals everything onto the EDT. FlatLaf (`FlatMacDarkLaf`) and the system tray are set up
in `AppMain`/`AppInstance`. Strings are localized via `I8N` from `resources/lang`.

Icons are glyphs from the bundled Remix Icon font (`resources/fonts/remixicon.ttf`), drawn by `FontIcon` via
`createIcon(RemixIcon.X, size, color)` (`utils/RemixIcon.kt`); to add one, add its codepoint from the same release's
`remixicon.css` to the `RemixIcon` enum. Logo and macOS tray icon are PNGs under `resources/images/`
(`logoImage`/`logoIcon`/`trayMacImage` in `UiHelper.kt`).

## Platform integration (`xdm-app/.../utils`)
`AutoStart` (login entry), `UrlScheme` (`xdm-app://` handler) and `KeepAwake` (idle-sleep inhibitor) register per-user,
without elevation, and are idempotent — each has a `sync()`-style entry point called from `AppContext.init` that rewrites
only what is missing or stale. They resolve the executable via `AppLauncher`, which prefers jpackage's
`jpackage.app-path`; the packaged launcher is `xdm-app.exe` on Windows and `xdm-app` elsewhere, while the display name is
"Xtreme Download Manager" (see `packaging/build-bundle.sh`). Only a packaged build (`AppLauncher.isPackaged`) enables
the login entry on first run, so `java -jar`/IDE runs never register a build-output jar. The settings toggle reads and
records the OS entry itself (`AutoStart.isEnabled()`), not just `config.runOnStartup`, because the entry can be removed
from outside XDM.

On Windows these use `utils/win/` — `Win32Registry` and `Win32Power`, thin `java.lang.foreign` bindings over
Advapi32/Kernel32. **Do not reintroduce `reg.exe`, `.reg` files or a `powershell.exe` helper.** The FFM classes are only
touched on Windows, and lazily (they cost metaspace). `SetThreadExecutionState` binds to the *calling thread*, so
`KeepAwake` owns one platform (never virtual) daemon thread while any download is active; the registry layer only writes
under `HKEY_CURRENT_USER`.

URL-scheme launch differs by platform: Windows/Linux start a second process and the URL arrives in `args` (handed to the
running instance by `BrowserIntegration.acquire` -> `/show`); macOS activates the running app and delivers an Apple event,
handled by the `setOpenURIHandler`/`AppReopenedListener` pair installed in `AppInstance.run`.

`OriginMarker` marks each finished download as coming from the internet, from `DownloadManager.onDownloadSuccess`
before the record turns FINISHED (setting `markDownloadedFiles`, on by default): the `Zone.Identifier` alternate data
stream on Windows (java.io, not NIO), `com.apple.quarantine` via `/usr/bin/xattr` on macOS (NIO's
`UserDefinedFileAttributeView` adds a `user.` prefix there, which Gatekeeper ignores), and `user.xdg.origin.url`/
`user.xdg.referrer.url` on Linux. A failed mark is logged and never fails the download. Design: [QUARANTINE.md](../QUARANTINE.md).

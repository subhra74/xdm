# XDM — Xtreme Download Manager

A desktop download manager. Maven multi-module Kotlin/JVM project with a Swing UI
(FlatLaf), driven by a companion browser extension that captures download/media requests
and forwards them to the running app over a local HTTP server.

## Modules

- **xdm-core** — download engine (HTTP/HLS/DASH), HTTP client, manifest parsers, pure-Kotlin transmuxer. No UI dependencies.
- **xdm-app** — Swing desktop application + browser-integration HTTP server. Main class: `xdm.app.AppMain`.

> `hls-muxer` is a superseded standalone experiment and is **not** part of the reactor build.

## Build & Run

Plain Maven (system `mvn`, no wrapper). Reactor root is `pom.xml`.

```bash
mvn -q clean package              # build all modules → xdm-app/target/xdm-app.jar (fat jar)
mvn -q -pl xdm-app -am package    # build xdm-app and its dependencies only
mvn -q test                       # run all tests
```

Run:

```bash
java -jar xdm-app/target/xdm-app.jar          # or run AppMain from the IDE
java -jar xdm-app/target/xdm-app.jar --no-gc  # disable the periodic System.gc() thread
```

## Packaging a native installer (jpackage)

The app ships as a self-contained native bundle built with `jlink` + `jpackage` (JDK 14+,
tested on JDK 25). First build the fat jar with `mvn -q clean package`.

### 1. Determine the required JDK modules

The minimal module set was derived with `jdeps` against the fat jar and verified at runtime:

```bash
jdeps --multi-release 25 --ignore-missing-deps \
      --print-module-deps xdm-app/target/xdm-app.jar
```

The app needs only these modules (everything else in the JDK is dropped):

| Module           | Why it's needed                                              |
|------------------|-------------------------------------------------------------|
| `java.base`      | Core runtime, networking, I/O (OkHttp uses raw sockets).    |
| `java.desktop`   | Swing/AWT UI, FlatLaf, system tray.                         |
| `java.prefs`     | Java Preferences (FlatLaf / config).                        |
| `java.xml`       | DASH `.mpd` manifest parsing (`DocumentBuilderFactory`).    |
| `java.logging`   | Soft dependency of OkHttp/okio.                             |
| `jdk.crypto.ec`  | Elliptic-curve TLS — required for HTTPS handshakes.\*       |
| `jdk.charsets`   | Non-Latin charsets for international URLs/filenames.        |

\* `jdk.crypto.ec` is **not** reported by `jdeps` (it's loaded as a security provider via
service binding), but without it HTTPS downloads fail with handshake errors. Keep it.

### 2. Build a trimmed runtime image with jlink

```bash
jlink \
  --add-modules java.base,java.desktop,java.prefs,java.xml,java.logging,jdk.crypto.ec,jdk.charsets \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
  --output xdm-app/target/runtime
```

This produces a ~53 MB self-contained runtime.

### 3. Package with jpackage

`jpackage` is **not** a cross-compiler — you must run both `jlink` (step 2) and `jpackage`
**on the target OS**. The runtime image and native launchers it produces are
platform-specific. The portable core of the command below is the same everywhere; only the
`--type` and the platform-specific shortcut/icon flags differ.

Stage the fat jar in a clean input directory (so jpackage doesn't bundle the rest of
`target/`) and define the shared GC options once:

```bash
rm -rf xdm-app/target/jpackage-in && mkdir -p xdm-app/target/jpackage-in
cp xdm-app/target/xdm-app.jar xdm-app/target/jpackage-in/

# Tuned GC/startup options baked into the app launcher (see Notes below).
JAVA_OPTS="-XX:+UseZGC -XX:MinMetaspaceFreeRatio=1 -XX:MaxMetaspaceFreeRatio=2 -XX:ZCollectionInterval=30 -XX:ZUncommitDelay=10 -XX:+ClassUnloading -XX:+ClassUnloadingWithConcurrentMark -XX:-AlwaysPreTouch -XX:-ZProactive -XX:-DisableExplicitGC -XX:TieredStopAtLevel=1 -XX:CICompilerCount=1 -Xms4m"
```

**macOS** (`.dmg`, the default on macOS — works out of the box):

```bash
jpackage \
  --name XDM \
  --app-version 0.0.1 \
  --vendor "XDM" \
  --input xdm-app/target/jpackage-in \
  --main-jar xdm-app.jar \
  --main-class xdm.app.AppMain \
  --runtime-image xdm-app/target/runtime \
  --dest xdm-app/target/dist \
  --icon packaging/xdm.icns \
  --java-options "$JAVA_OPTS"
```

**Windows** (`.exe` via [WiX Toolset](https://wixtoolset.org/) v3.x on `PATH`; use `--type msi` for MSI):

```bash
jpackage \
  --type exe \
  --name XDM \
  --app-version 0.0.1 \
  --vendor "XDM" \
  --input xdm-app/target/jpackage-in \
  --main-jar xdm-app.jar \
  --main-class xdm.app.AppMain \
  --runtime-image xdm-app/target/runtime \
  --dest xdm-app/target/dist \
  --icon packaging/xdm.ico \
  --win-menu --win-shortcut --win-dir-chooser \
  --java-options "$JAVA_OPTS"
```

**Linux — Debian/Ubuntu (`.deb`) or Fedora/RHEL (`.rpm`):** swap `--type deb` for
`--type rpm`. `.deb` needs `fakeroot` + `dpkg`; `.rpm` needs `rpm-build`.

```bash
jpackage \
  --type deb \
  --name xdm \
  --app-version 0.0.1 \
  --vendor "XDM" \
  --input xdm-app/target/jpackage-in \
  --main-jar xdm-app.jar \
  --main-class xdm.app.AppMain \
  --runtime-image xdm-app/target/runtime \
  --dest xdm-app/target/dist \
  --icon packaging/xdm.png \
  --linux-shortcut --linux-menu-group "Network" \
  --linux-package-name xdm \
  --java-options "$JAVA_OPTS"
```

**Linux — Arch Linux:** jpackage has no native Arch (`.pkg.tar.zst`) packager, so build a
self-contained **app-image** and wrap it in a `PKGBUILD`. First produce the app-image:

```bash
jpackage \
  --type app-image \
  --name xdm \
  --app-version 0.0.1 \
  --input xdm-app/target/jpackage-in \
  --main-jar xdm-app.jar \
  --main-class xdm.app.AppMain \
  --runtime-image xdm-app/target/runtime \
  --dest xdm-app/target/dist \
  --java-options "$JAVA_OPTS"
# → xdm-app/target/dist/xdm/  (contains bin/xdm launcher + lib/ + runtime)
```

Then a minimal `PKGBUILD` that installs it under `/opt` and exposes a launcher + desktop entry:

```bash
# PKGBUILD
pkgname=xdm
pkgver=0.0.1
pkgrel=1
pkgdesc="Xtreme Download Manager"
arch=('x86_64')
license=('custom')
options=(!strip)            # don't strip the bundled JVM
package() {
  # assumes the jpackage app-image dir 'xdm/' sits next to this PKGBUILD
  install -dm755 "$pkgdir/opt"
  cp -r "$srcdir/../xdm" "$pkgdir/opt/xdm"
  install -dm755 "$pkgdir/usr/bin"
  ln -s /opt/xdm/bin/xdm "$pkgdir/usr/bin/xdm"
  install -Dm644 "$srcdir/../packaging/xdm.png" "$pkgdir/usr/share/pixmaps/xdm.png"
  install -Dm644 /dev/stdin "$pkgdir/usr/share/applications/xdm.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=XDM
Exec=/opt/xdm/bin/xdm
Icon=xdm
Categories=Network;
EOF
}
```

Build the package with `makepkg -f` (run from the directory holding the `PKGBUILD` and the
`xdm/` app-image), then install with `sudo pacman -U xdm-0.0.1-1-x86_64.pkg.tar.zst`. For a
shareable recipe, publish this as an AUR package that pulls the release jar in `prepare()`
instead of bundling the prebuilt app-image.

Notes:

- `--app-version` must be numeric (no `-SNAPSHOT`).
- `--icon` format is per-platform: `.icns` (macOS), `.ico` (Windows), `.png` (Linux). The
  `packaging/` paths above are placeholders — point them at your actual icon files or drop
  the flag to use the default icon.
- The `$JAVA_OPTS` string is the tuned GC/startup configuration baked into the app launcher:
  ZGC with aggressive metaspace reclamation, concurrent class unloading, minimal JIT
  tiering, and a tiny initial heap for fast startup and low idle footprint. Explicit GC is
  kept enabled (`-XX:-DisableExplicitGC`) so the app's periodic `System.gc()` works; pass
  `--no-gc` at runtime to suppress it.

# Packaging and distribution

How XDM 9 is built into installers for Windows, macOS and Linux, how users update it, and how it replaces XDM 8.

**Status:** design, except §8 (the full-JIT override), which is implemented. Everything else describes planned
changes to `packaging/build-bundle.sh`, `packaging/build-bundle.ps1` and new installer sources. Each claim is
tagged:
- ✅ **verified**: checked in this repo, the old XDM 8 repo, or upstream source code, or run on this machine
- 🔎 **to verify**: must be tested on the target OS before release

Related: [AUTOSTART.md](AUTOSTART.md) (login entry, JVM flags), [APPCDS.md](APPCDS.md) (AppCDS archive),
[docs/build.md](docs/build.md), [docs/ui-and-platform.md](docs/ui-and-platform.md).

---

## 1. Decisions at a glance

| Topic | Decision |
|---|---|
| Build tool | `jlink` + `jpackage --type app-image` only. Installers are made by separate tools, never by jpackage |
| Windows installer | Own WiX `.wxs`. Per-machine by default (Program Files, admin), per-user from the command line only |
| macOS installer | `hdiutil` dmg of the app image, ad-hoc signed |
| Linux packages | **nfpm** (one config → deb, rpm, Arch) plus a plain `.tar.gz` |
| Architectures | Separate builds: Windows x64 / ARM64, macOS x64 / ARM64, Linux x64 / ARM64 |
| Launchers | One launcher, `xdm-app`. The `--add-launcher` hack goes away; installers supply the display name |
| JIT | C1 only when Conscrypt's native library ships in the build (every target except Windows ARM64). Users can switch to full tiered in Advanced settings (§8) |
| Code signing | None for now. Apply to SignPath Foundation for Windows (§10). macOS stays ad-hoc signed |
| Updates | Semi-manual: the app shows a banner, the user downloads the new installer from the website and installs over the old version (§9) |
| XDM 8 migration | Windows: same MSI UpgradeCode, same Run key name; detect the Store (MSIX) version. Linux: take over the old package names (§11) |

---

## 2. Constraints

- **Free and open source; no paid code signing.** Windows SmartScreen and macOS Gatekeeper warn on first install.
- **Updates must go through the website**, which carries ads. So no auto-update, and channels that update behind
  the site's back (Store MSIX, winget, Homebrew, Flathub/Snap, apt/rpm repos) are optional extras at best (§9).
- **XDM 8 users must end up with exactly one XDM.** XDM 8 and XDM 9 both listen on `127.0.0.1:8597`; with two
  installed, one fails to start and the browser extension behaves unpredictably.
- **Windows: per-machine by default.** Installing to Program Files with admin rights triggers fewer antivirus
  warnings.

---

## 3. Build pipeline

```
mvn package ──► jlink runtime ──► strip foreign natives ──► decide JIT flags ──► AppCDS record/dump
          ──► jpackage --type app-image (launcher "xdm-app") ──► post-process ──► OS installer
```

### 3.1 Build matrix

Each target is built natively on its own architecture. Nothing is cross-built: the jlink runtime, the jpackage
launcher and the AppCDS archive are all specific to one architecture. ✅

| Target | GitHub Actions runner | Conscrypt native | JIT | Output |
|---|---|---|---|---|
| Windows x64 | `windows-latest` | ✅ | C1 only | `xdm-<ver>-x64.msi` |
| Windows ARM64 | `windows-11-arm` | ❌ | full tiered | `xdm-<ver>-arm64.msi` |
| macOS ARM64 | `macos-latest` | ✅ | C1 only | `xdm-<ver>-arm64.dmg` |
| macOS x64 | an Intel macOS runner 🔎 (check the current label) | ✅ | C1 only | `xdm-<ver>-x64.dmg` |
| Linux x64 | `ubuntu-latest` | ✅ | C1 only | deb, rpm, Arch, tar.gz |
| Linux ARM64 | `ubuntu-24.04-arm` | ✅ | C1 only | deb, rpm, Arch, tar.gz |

- ✅ Linux and Windows ARM64 runners are free for public repositories (GitHub, Aug 2025).
- ✅ Conscrypt 2.7.0 (`xdm-app/pom.xml`) ships natives for windows-x86_64, linux-x86_64, linux-aarch_64,
  osx-x86_64 and osx-aarch_64. There is no windows-aarch64 build (checked in the jar and on Maven Central).
- **Build with a vendor JDK (e.g. Temurin), not a distro package.** The runtime and launcher come from the build
  JDK, so the JDK's glibc baseline becomes XDM's. Vendor builds target old glibc; a distro OpenJDK may need the
  build host's newer one. 🔎 After building, check the highest `GLIBC_` symbol version in
  `lib/runtime/lib/server/libjvm.so` and the launcher.

### 3.2 JIT flags follow what ships (planned)

`-XX:TieredStopAtLevel=1` and `-XX:CICompilerCount=1` leave the fixed option list in both scripts. They are
added right after the foreign-natives strip, and before AppCDS record/dump, so the archive is built with the
launcher's exact flags:

```bash
if jar tf "$INPUT_DIR/$MAIN_JAR" | grep -q '^META-INF/native/.*conscrypt'; then
  JAVA_OPTIONS+=(-XX:TieredStopAtLevel=1 -XX:CICompilerCount=1)   # Conscrypt does TLS natively
else
  JAVA_OPTIONS+=(-XX:CICompilerCount=2)                           # tiered needs >= 2 compiler threads
fi
```

- ✅ The two flags must move together. With C2 enabled, `CICompilerCount=1` is rejected at JVM start.
- **Take the architecture from the bundled runtime** (`$RUNTIME_DIR/bin/java -XshowSettings:properties` →
  `os.arch`), not from the host (`uname -m`, `$env:PROCESSOR_ARCHITECTURE`). An x64 JDK running emulated on ARM64
  Windows then correctly yields an x64 bundle.
- **Fix `Remove-ForeignNatives` in `build-bundle.ps1`.** It always keeps `windows-x86_64.dll`, including on
  ARM64, with the comment "runs it under emulation". That comment is wrong: an ARM64 JVM cannot load an x64 DLL.
  On ARM64 it must keep no Conscrypt library, or the check above picks C1 only. The same wrong comment appears in
  `build-bundle.sh`.
- The JIT flags are not among the settings the JVM validates when loading a CDS archive, so the C1 and
  full-tiered modes should share one archive. 🔎 Not yet tested: the §8.4 build had no class list, so no archive.
  Check with `-Xlog:cds` under the override once a class list is committed.

### 3.3 App image (planned)

- **One launcher, `xdm-app`** (`jpackage --name xdm-app`). The display name comes from the WiX shortcuts, the
  macOS bundle name and `CFBundleDisplayName`, and the Linux `.desktop` `Name=`. Remove `--add-launcher` and the
  launcher `.properties` file. `AppLauncher.preferStableLauncher` already copes with a single launcher.
- **Write `app/.package`**: one line, the package name (§4.1). The jpackage launcher only reads a per-user `.cfg`
  when this file exists (§8). ✅ An app image does not contain it (checked `build/dist`: only `.jpackage.xml` and
  the `.cfg` files). On Linux, only the deb and rpm packages ship it (§7.3).
- **Delete `-XX:MetaspaceReclaimPolicy=aggressive`** from both scripts. ✅ JDK 25 prints "Ignoring option
  MetaspaceReclaimPolicy; support was removed in 21.0" on every start (seen while testing §8).

### 3.4 AppCDS

Unchanged from [APPCDS.md](APPCDS.md) §11, with these changes:
- **Class lists:** `cds/windows.classlist`, `cds/mac.classlist` and `cds/linux.classlist` are shared by x64 and
  ARM64, since both load the same Conscrypt classes. Windows ARM64 gets `cds/windows-aarch64.classlist` (it loads
  the JDK's TLS classes instead); the script looks for `<os>-<arch>` first and falls back to `<os>`.
- **Windows per-machine installs:** `CdsJarPin` cannot re-pin the jar under Program Files, which isn't writable,
  so the installed jar must already carry the pinned mtime (§5.6).

### 3.5 Conscrypt native loading (optional hardening)

✅ Conscrypt's `NativeLibraryLoader` first extracts its library from the jar into a work folder (the temp
folder, or the one named by the `org.conscrypt.native.workdir` property) and loads it from there. If that fails,
it falls back to `System.loadLibrary`, which searches `java.library.path`.

Extraction fails when `/tmp` is mounted `noexec` or antivirus blocks DLLs in `%TEMP%`. XDM then runs on the JDK's
TLS. The per-user full-JIT override (§8) exists partly for this case.

Hardening, 🔎 to verify on each OS:
1. At build time, extract the one remaining Conscrypt library into `app/` and delete it from the jar.
2. Add `-Djava.library.path=$APPDIR` to the launcher options.

The extraction step then finds no resource, and the fallback loads the library straight from the install folder.
Nothing is written to temp, no DLL ends up in `%TEMP%`, and the library is shipped once.

---

## 4. Names and paths

### 4.1 Package name (decision needed)

One name, written to `app/.package`, used everywhere:

| Where | Effect |
|---|---|
| deb / rpm / Arch package name | Must equal `.package`: the Linux launcher looks for `~/.local/<package>/` using the name dpkg/rpm reports |
| Per-user `.cfg` folder | Windows `%LOCALAPPDATA%\<pkg>\`, macOS `~/Library/Application Support/<pkg>/`, Linux `~/.local/<pkg>/` |

**Proposal: `xdman`.** It's the old deb package name, so Debian/Ubuntu users upgrade in place, and the rpm/Arch
packages declare that they replace `xdman_gtk` (§11.3).

### 4.2 Install locations

| OS | Location |
|---|---|
| Windows per-machine (default) | `C:\Program Files\XDM\` (XDM 8 used `C:\Program Files (x86)\XDM\`, 32-bit) |
| Windows per-user (command line) | `%LOCALAPPDATA%\Programs\XDM\` |
| macOS | `/Applications/Xtreme Download Manager.app` |
| Linux (packages) | `/opt/xdman/` with `bin/xdm-app`, `lib/app/`, `lib/runtime/`; `/usr/bin/xdman` → `bin/xdm-app` |
| Linux (tar.gz) | Wherever the user unpacks it |

User state stays in `~/.xdm-app/` on every OS.

---

## 5. Windows: MSI (planned)

WiX v5 or later (MSBuild SDK `WixToolset.Sdk`) with `WixToolset.Util.wixext` and `WixToolset.UI.wixext`. Built
from the app image. XDM 8's `product.wxs` (WiX v3) is the reference for the licence dialog, the "Launch XDM"
checkbox and the version conditions.

### 5.1 Package

```xml
<Package Name="Xtreme Download Manager" Manufacturer="Subhra Das Gupta"
         Version="$(Version)" UpgradeCode="3E462F34-3D19-4247-AD64-1B74703555D6"
         Scope="perMachineOrUser" Compressed="yes">
  <MajorUpgrade Schedule="afterInstallInitialize"
                DowngradeErrorMessage="A newer version of Xtreme Download Manager is already installed." />
  <Launch Condition="Installed OR VersionNT &gt; 602" Message="Xtreme Download Manager needs Windows 10 or later." />
  <InstallExecuteSequence>
    <!-- 5.3: close XDM before the old version is removed. _X64 / _A64 per platform. -->
    <Custom Action="override Wix4CloseApplications_X64" Before="RemoveExistingProducts" />
  </InstallExecuteSequence>
```

- ✅ **`Scope="perMachineOrUser"`** sets `ALLUSERS=2` without `MSIINSTALLPERUSER`, so the default install is
  per-machine. `msiexec /i xdm.msi MSIINSTALLPERUSER=1` installs per-user. (`perUserOrMachine` would do the
  reverse and default to per-user.) Checked in the WiX compiler source (`Compiler_Package.cs`). 🔎 Check that a
  per-user install from the command line doesn't raise a UAC prompt.
- **Platform:** build x64 and arm64 separately (`-arch x64` / `-arch arm64`), both with the same UpgradeCode, so
  either upgrades the other.
- ✅ **UpgradeCode `3E462F34-…`** is XDM 8's (`XDM.Win.Installer/product.wxs`). Reusing it makes the XDM 9 MSI
  uninstall an XDM 8 that was installed from XDM 8's MSI. Drop jpackage's `6f9619ff-8b86-d011-b42d-00c04fc964ff`:
  it's the sample GUID from Microsoft's documentation.
- **Version:** MSI compares only the first three fields; 9.0.x > 8.0.25 ✅.
- **Schedule the upgrade `afterInstallInitialize` explicitly.**
  - ✅ WiX's default is `afterInstallValidate` (`Compiler.cs`, `ParseMajorUpgradeElement`).
  - Both remove XDM 8 completely before XDM 9's files and first run. XDM 8's uninstall deletes the `Run\XDM`
    value (its MSI owned it), and XDM 9 re-creates it on first run (§5.4). Never use `afterInstallExecute` or
    later: XDM 8's removal would then delete XDM 9's Run value.
  - `afterInstallInitialize` is needed so the close step can run before the removal (§5.3).
- **OS condition:** kept from XDM 8 (`VersionNT > 602`). On Windows 10, Windows Installer reports the Windows 8.1
  version, so this lets 8.1 through too; JDK 25 itself requires Windows 10.

### 5.2 Files, shortcuts, UI

- Harvest the app image into `ProgramFiles64Folder\XDM` (WiX `Files` element or heat).
- Start menu and desktop shortcuts named "Xtreme Download Manager", pointing at `xdm-app.exe`, not advertised.
- Licence dialog (`gpl-3.0.rtf`), the `WixUI_InstallDir` dialog set, and on the last page the checkbox "Launch
  Xtreme Download Manager" (checked). It runs `LaunchXDM` from the exit dialog, as the logged-in user without
  admin rights (same as XDM 8).

### 5.3 Closing a running XDM

The install must end any running XDM, both XDM 9 and XDM 8 (both run as `xdm-app.exe`):

```xml
<util:CloseApplication Id="CloseXdm" Target="xdm-app.exe"
    CloseMessage="yes" ElevatedCloseMessage="yes"
    TerminateProcess="1" Timeout="5000" RebootPrompt="no" />
```

Checked in the WiX Util source (`UtilCompiler.cs`, `CloseApps.cpp`, `UtilExtension_Platform.wxi`):
- ✅ `Timeout` is in **milliseconds** (it is passed to `SendMessageTimeout`/process wait; the default is 5000).
- ✅ **`RebootPrompt` defaults to yes.** It must be set to `no`, or a surviving process causes a reboot prompt.
- ✅ `TerminateProcess="<exit code>"` kills the process with `TerminateProcess` from the elevated deferred action,
  so it reaches XDM running under other users' sessions too.
- ✅ **Sequencing:** WiX schedules the close step (`Wix4CloseApplications_<X64|A64>`, declared `virtual`)
  `Before="InstallFiles"`. That is too late in two ways:
  1. **It runs after the major upgrade removes the old version.** XDM 8 would still be running while its own
     uninstall ran; its files would be left for deletion at the next reboot, and a reboot prompt would follow.
     **Fix:** override it to `Before="RemoveExistingProducts"`, with the upgrade `afterInstallInitialize` (§5.1).
     The immediate half then queues the elevated terminate step into the install script ahead of the removal.
     🔎 Check the built MSI's `InstallExecuteSequence` (e.g. `wix msi decompile`): `InstallInitialize` <
     `Wix4CloseApplications_X64` < `RemoveExistingProducts`.
  2. **It runs after `InstallValidate`**, where the files-in-use check happens, so the Restart Manager "close
     these applications?" dialog can appear first. Set `MSIRESTARTMANAGERCONTROL=Disable`. The legacy
     files-in-use dialog only lists processes with a titled window, so a tray-only XDM should not show up.
     🔎 Verify: install over a running XDM (closed to the tray, and with its window open); no dialog should appear.
  - It can't move before `InstallValidate`: its elevated deferred half can only be queued once the install script
    has started (after `InstallInitialize`).
- **Terminating is safe for data.** State files are written through `AtomicIO`; downloads resume from their last
  saved progress. Later, a graceful `xdm-app.exe --quit` could run before this step (planned, not required).
- Other users' XDM is not relaunched; it starts at their next login through autostart.

### 5.4 Autostart and the `xdm-app://` scheme are not installer-owned

The MSI writes **no** Run value and **no** scheme keys. The app owns them (per-user, HKCU), as today:

| Path | Result |
|---|---|
| Fresh install / XDM 8 upgrade | "Launch XDM" starts XDM 9 as the real user. No `~/.xdm-app` config yet, so first run enables autostart (`Run\XDM`, the same value name XDM 8 used ✅ `PlatformHelper.cs:340`) and registers `xdm-app://` in HKCU |
| 9.x → 9.y | Same exe path; `AutoStart.sync()` keeps the entry. If the user turned autostart off, it stays off |
| Silent install (`/qn`) | No finish page; registration happens at the first manual start |
| Other Windows users on a per-machine install | Each registers at their own first start |

Why not have the installer write them: a deployment tool running the MSI as SYSTEM, or "Run as administrator"
with a different account, would write HKCU entries into the wrong profile. And an installer-owned Run value comes
back on MSI repair after the user turned autostart off.

**On uninstall**, `RemoveRegistryValue`/`RemoveRegistryKey` remove `HKCU\…\Run\XDM` and
`HKCU\Software\Classes\xdm-app`, and `RemoveFolder` removes `%LOCALAPPDATA%\<pkg>` (§8). This only cleans up for
the user who runs the uninstall.

### 5.5 Blocking conflicting installs

| Situation | Detection | Action |
|---|---|---|
| XDM 8 from its MSI, per-machine | Same UpgradeCode | Removed by the major upgrade (per-machine mode) |
| XDM 8 from its MSI, while XDM 9 installs **per-user** | `RegistrySearch` for `HKLM\Software\Xtreme Download Manager` `Installed2` (32-bit view; ✅ written by XDM 8's `product.wxs`) | Block: "Uninstall XDM 8 first" |
| XDM 9 per-machine present, per-user install requested | `RegistrySearch` for a marker XDM 9 writes in HKLM | Block |
| XDM 8 from the **Microsoft Store (MSIX)** | `DirectorySearch` for `[LocalAppDataFolder]Packages\<XDM 8 package family name>` | Block: "Uninstall XDM 8 from Settings → Apps first" |

- An MSIX package isn't an MSI product: the UpgradeCode can't see or remove it, its manifest registers its own
  startup task and `xdm-app://`, and removing it needs PowerShell or the packaging API (both ruled out).
- **Needed:** XDM 8's Store package family name (in its MSIX manifest or Partner Center).
- **App-side backstop:** if port 8597 is taken by something other than XDM 9, show a clear message naming XDM 8.
- **Optional:** a final XDM 8 Store update that points users to XDM 9 and disables its startup task.

### 5.6 AppCDS jar timestamp under Program Files

The jar is pinned to 2020-01-01T00:00:00Z at build time (§3.4).
- ✅ Windows Installer installs files with the timestamp stored in the cabinet, not the install time (per
  installer vendor docs).
- 🔎 **Verify on a machine in a different timezone from the build machine** (cabinet times are DOS local times).
  If it doesn't match, XDM's log says `CDS: cannot re-pin …` on every start.
- **Fallback if it drifts:** add a headless `xdm-app.exe --pin-cds` mode (calls `CdsJarPin.repin()` and exits,
  before any UI or port binding). Run it as a deferred, non-impersonated custom action after `InstallFiles`, with
  condition `NOT REMOVE`. **No PowerShell custom actions:** `msiexec` spawning `powershell.exe` is a common
  antivirus heuristic, and CLAUDE.md rules out PowerShell helpers.
- Once the MSI owns the pin, lower `CdsJarPin`'s per-start `cannot re-pin` error to a debug message.

---

## 6. macOS: dmg (planned)

1. `jpackage --type app-image --name xdm-app` → rename the bundle to `Xtreme Download Manager.app`
   (`CFBundleExecutable` stays `xdm-app`).
2. Patch `Info.plist`: `CFBundleDisplayName`, `CFBundleName=XDM`, `CFBundleURLTypes` for `xdm-app://` (as
   `build-bundle.sh` does today).
3. Write `Contents/app/.package` (§3.3).
4. **Re-sign ad-hoc:** `codesign --force --deep -s - "Xtreme Download Manager.app"`, then
   `codesign --verify --deep --strict`.
   - ✅ Required. The current `build/dist` bundle fails verification (`invalid Info.plist (plist or signature have
     been modified)`) because the plist is patched after jpackage signs it.
   - A downloaded bundle with a broken signature shows "is damaged and can't be opened", with no way past it
     except Terminal.
5. `hdiutil create` a dmg with the app and an `/Applications` link. x64 and ARM64 are separate dmgs.

Notes:
- **First launch:** unsigned apps need System Settings → Privacy & Security → **Open Anyway** (Sequoia removed
  the right-click → Open bypass). Put this on the download page.
- ✅ **Homebrew:** since 2026-09-01 the official cask repo disables casks that fail Gatekeeper, so an unsigned
  XDM can't be listed there. A tap of our own would work but updates without the website (§9).
- 🔎 **Conscrypt's dylibs declare macOS 26 as their minimum.** They load on macOS 15.6 (✅ this machine); older
  versions are untested. Failure is not fatal: XDM falls back to JDK TLS, and the About dialog shows it (§8.6).
- **Updating:** the user quits XDM and drags the new app over the old one. Finder asks to replace it.

---

## 7. Linux: nfpm packages (planned)

### 7.1 Why nfpm

It replaces XDM 8's three hand-written scripts (`app/packaging/make-{deb,rpm,arch}-pkg`) and jpackage's deb/rpm
mode:
- one YAML file produces deb, rpm and Arch packages (also apk)
- a single Go binary that runs on any build host
- built-in `conflicts`/`replaces`/`provides`, install scripts, per-packager `overrides`, and environment
  variables in the config (e.g. `arch: ${ARCH}`) ✅

Its only drawback is that dependencies are listed by hand; jpackage detected them automatically.

✅ Checked in the nfpm source:
- `overrides` accept `depends`, `recommends`, `replaces` and `conflicts`
- for rpm, `replaces` is written as `Obsoletes` (`rpm/relations.go`)
- `arch` `amd64`/`arm64` becomes `x86_64`/`aarch64` for rpm
- a `symlink` entry's `src` is the target and `dst` is the link

### 7.2 Layout and metadata

```yaml
name: xdman
arch: ${ARCH}              # amd64 / arm64 (nfpm maps to x86_64 / aarch64 for rpm)
version: ${VERSION}
maintainer: Subhra Das Gupta
description: Open source download accelerator and video downloader.
license: GPL-3.0-or-later
homepage: https://xtremedownloadmanager.com
contents:
  - src: build/image/xdm-app/        # the jpackage app image
    dst: /opt/xdman/
  - dst: /opt/xdman/lib/app          # see 7.3: rpm must own these directories
    type: dir
  - dst: /opt/xdman/lib/runtime
    type: dir
  - src: /opt/xdman/bin/xdm-app
    dst: /usr/bin/xdman
    type: symlink
  - src: packaging/linux/xdman.desktop
    dst: /usr/share/applications/xdman.desktop
  - src: packaging/icons/xdm.png
    dst: /usr/share/icons/hicolor/256x256/apps/xdman.png
  - src: packaging/linux/package-name     # "xdman" -> app/.package, deb and rpm only (7.3)
    dst: /opt/xdman/lib/app/.package
    packager: deb
  - src: packaging/linux/package-name
    dst: /opt/xdman/lib/app/.package
    packager: rpm
scripts:
  preinstall: packaging/linux/preinstall.sh    # pkill -x xdm-app || true
  postinstall: packaging/linux/postinstall.sh  # update-desktop-database, gtk-update-icon-cache (if present)
  postremove: packaging/linux/postremove.sh
overrides:
  deb:
    depends: [libx11-6, libxext6, libxrender1, libxtst6, libxi6, libfreetype6, fontconfig]
    recommends: [ffmpeg]
  rpm:
    depends: [libX11, libXext, libXrender, libXtst, libXi, freetype, fontconfig]
    replaces: [xdman_gtk]        # nfpm writes Obsoletes for rpm
    conflicts: [xdman_gtk]
  archlinux:
    depends: [libx11, libxext, libxrender, libxtst, libxi, freetype2, fontconfig]
    replaces: [xdman_gtk]
    conflicts: [xdman_gtk]
```

`xdman.desktop`: `Name=Xtreme Download Manager`, `Exec=/opt/xdman/bin/xdm-app %U`, `Icon=xdman`,
`Categories=Network;FileTransfer;`, `MimeType=x-scheme-handler/xdm-app;`, `StartupWMClass` set to the window
class 🔎.

Notes:
- XDM 8's `GTK_USE_PORTAL=1` wrapper and GTK3/ffmpeg dependencies belonged to the .NET/GTK UI and are dropped.
- 🔎 The dependency list is the usual set for Swing on a jlink runtime; check it with `ldd` on
  `lib/runtime/lib/*.so` in the image.

### 7.3 How the jpackage launcher behaves on Linux (why the details above matter)

✅ From `LinuxLauncherLib.cpp` / `Package.cpp` (OpenJDK master):
1. **On every start** the launcher runs `rpm -qf <launcher>`, then `dpkg -S <launcher>`, to find the package
   that owns it.
2. **If an owner is found**, it lists that package's files (`rpm -ql` / `dpkg -L`) and takes the **first path
   ending in `/app`** as the app folder and the **first ending in `/runtime`** as the runtime. It also looks for a
   per-user `.cfg` in `~/.local/<package>/`, then `~/.<package>/`.
3. **If no owner is found** (tar.gz, and the Arch package, since pacman is neither), it uses the standard layout
   next to the launcher (`../lib/app`, `../lib/runtime`) and there is no per-user `.cfg`.

Consequences:
- **The rpm must own `/opt/xdman/lib/app` and `/opt/xdman/lib/runtime` as directories** (`type: dir` above).
  Otherwise `rpm -ql` may not list them, the launcher gets no app folder, and XDM fails to start. 🔎 Test on
  Fedora with `rpm -ql xdman | grep -E '/(app|runtime)$'`. No other packaged path may end in `/app` or
  `/runtime` before those.
- **`.package` is shipped only in the deb and rpm.** The app uses it to decide whether the full-JIT option can
  work (§8); on Arch and tar.gz the launcher would ignore the override, so the option stays locked.
- The `rpm`/`dpkg` queries on every start already happen with jpackage's own packages; they are not new cost.

### 7.4 Closing a running XDM

`preinstall.sh` runs `pkill -x xdm-app || true`, matching the Windows behaviour (§5.3): every user's XDM is ended
before files are replaced. Autostart brings it back at the next login.

### 7.5 tar.gz

The same app image as `xdm-<ver>-linux-<arch>.tar.gz`, without `.package`. Users run `bin/xdm-app`; the app
registers its own `.desktop` scheme handler and autostart entry at first run (per-user, as today).

---

## 8. Full-JIT override (implemented)

### 8.1 What the user sees

Settings → Advanced → System → **Full JIT compiler**: "Faster HTTPS where Conscrypt cannot load, and faster video
muxing, at the cost of more memory. Takes effect after a restart."

| Build / install | Toggle |
|---|---|
| C1-only build, override supported (Windows/macOS with `.package`, Linux deb/rpm) | Editable |
| Build without C1 flags (Windows ARM64) | On, locked |
| No `.package` (Arch, tar.gz, today's builds), or a dev run | Off, locked |

### 8.2 How the jpackage launcher reads its `.cfg`

✅ From OpenJDK's `AppLauncher.cpp`, `CfgFile.cpp`, `WinLauncher.cpp`, `MacLauncher.cpp`, `LinuxLauncherLib.cpp`:
- **The launcher loads the JVM in its own process** (through `jli`), so the process keeps the launcher's name.
- **It reads `<launcher name>.cfg`.** It checks a list of folders and uses the **first file found**; the app
  folder is last:

  | OS | Per-user folders checked first | Only if |
  |---|---|---|
  | Windows | `%LOCALAPPDATA%\<pkg>\`, then `%APPDATA%\<pkg>\` | `app\.package` exists |
  | macOS | `~/Library/Application Support/<pkg>/` | `Contents/app/.package` exists |
  | Linux | `~/.local/<pkg>/`, then `~/.<pkg>/` | the launcher belongs to an installed deb/rpm (§7.3) |

- **A found file replaces the installed one entirely**; nothing is merged.
- **Sections:** `[Application]` (`app.classpath`, `app.mainclass`, `app.mainjar`, `app.runtime`, …),
  `[JavaOptions]` (`java-options=`, one JVM argument per line), `[ArgOptions]` (`arguments=`, default app
  arguments used only when the command line has none).
- **Variables:** `$APPDIR`, `$BINDIR` and `$ROOTDIR` expand to the install paths wherever the file lives. Since
  JDK 25 any environment variable expands too (JDK-8341641), but an **unset variable stays as literal text**, so
  the JVM gets an option like `$VAR` and refuses to start. That's why the override is a file, not an environment
  variable.
- **Command-line arguments only replace `[ArgOptions]`** and go to `main()`. There is no way to pass JVM options
  on the launcher's command line.

### 8.3 What the app does

Code: `xdm-app/src/main/java/xdm/app/utils/JitOverride.kt`.
- **Finding the installed `.cfg`:** from `jpackage.app-path` (Windows `<root>\app`, Linux `<root>/lib/app`,
  macOS `Contents/app`). The package name comes from `app/.package`.
- **Turning it on** writes, for every installed `*.cfg`, a per-user copy with the `TieredStopAtLevel` and
  `CICompilerCount` lines dropped and `java-options=-Dxdm.jit=full` added. The write goes through a temp file and
  a rename. It deliberately doesn't use `AtomicIO`, whose footer and `.bak` files the launcher would misparse.
- **Turning it off** deletes the copies.
- **`JitOverride.sync()`** runs at the start of `AppMain`, after `CdsJarPin.repin()`:
  - regenerates an existing copy when the installed file changed (after an upgrade, the new flags apply from the
    second start)
  - deletes it if the install no longer runs C1 only
  - logs `Running C1 only` / `Running full tiered (C1+C2)`
- **Mode:** `isRunningFull` is `-Dxdm.jit=full` (set only by the override) or a build without C1 flags.
- **Fixed names:** `xdm-app.jar` and `xdm.app.AppMain` must never be renamed. A stale override from an older
  version would point at them, and a missing jar means XDM can't start. **Recovery:** delete the per-user
  `xdm-app.cfg`.

### 8.4 Verified on macOS 15.6 / ARM64 / JDK 25

Test setup: a copy of `build/dist/Xtreme Download Manager.app` with `Contents/app/.package` added, and an override
in a scratch `$HOME` whose main class was a probe that prints `xdm.jit`, run with `-XX:+PrintFlagsFinal`.

| `.cfg` used | `xdm.jit` | `TieredStopAtLevel` | `CICompilerCount` |
|---|---|---|---|
| Per-user override | `full` | 4 (default) | 4 (ergonomic) |
| Installed (override removed) | — | 1 | 1 |

`JitOverride` itself was also run against a fake bundle. It wrote exactly the installed file minus the two C1
lines, plus the marker and a comment header. `setEnabled(false)` removed it.

🔎 Still to test: Windows (`%LOCALAPPDATA%`) and Linux deb/rpm (`~/.local/<pkg>`).

### 8.5 Build requirement

The toggle stays locked until the build writes `app/.package` (§3.3). That's the only build change the feature
needs.

### 8.6 About dialog

Shows `Conscrypt <major>.<minor>.<patch>` when its native library loaded, otherwise `Conscrypt not available
(using JDK TLS)`. This tells users and bug reports whether TLS runs natively, and whether the full-JIT option is
worth enabling.

---

## 9. Updates

- **In the app:** at start, `UpdateChecker` compares `app-version.json` with the latest GitHub release. If a newer
  version exists, the banner at the bottom of the main window (`UpdatePanel`, `install-fill` icon) offers a button
  that opens the download page. **Before release:** restore the `if (info != null)` condition in
  `AppWindow.checkForUpdates` (currently commented out so the banner always shows for testing).
- **Installing the update:** the user downloads the new installer and runs it over the old version:
  - **Windows:** the major upgrade replaces the old version in place; the running XDM is closed first (§5.3)
  - **macOS:** quit XDM, drag the new app over the old one
  - **Linux:** `apt install ./xdman_<ver>_<arch>.deb`, `dnf install ./xdman-<ver>.<arch>.rpm`, or
    `pacman -U`; running XDM is ended by `preinstall` (§7.4)
- **Channels that update without the website:** Microsoft Store (MSIX auto-updates; the MSI/EXE listing type is
  starting to update too), winget, a Homebrew tap, Flathub/Snap, and apt/rpm repositories. Each one is an
  explicit choice between more users and fewer site visits. None is planned.

---

## 10. Code signing

- **Windows:** apply to **SignPath Foundation** (free OV certificate for OSS).
  - Requirements: an OSI licence (GPL-3.0 ✅), releases built in CI from the public repo, MFA for maintainers, a
    privacy policy that mentions the update check, and a working uninstaller.
  - The publisher shows as "SignPath Foundation".
  - Signing order: `xdm-app.exe` and the DLLs, then the MSI.
  - Until then, SmartScreen warns on every release.
  - Paid fallbacks: Certum Open Source (~€25–69/yr); Azure Artifact Signing (individuals only in the US or
    Canada).
- **macOS:** no free option. The $99/yr Apple Developer Program is waived only for nonprofit organisations. Stay
  ad-hoc signed (§6 step 4) and document Open Anyway. Consider funding the fee through sponsors.
- **Linux:** nothing needed for downloaded packages (GPG only if we ever host repositories).

---

## 11. Migrating from XDM 8

### 11.1 What XDM 8 left behind ✅ (from `~/Downloads/xdm-master`)

| OS | Install | Autostart | Scheme |
|---|---|---|---|
| Windows (MSI) | Per-machine, 32-bit, `Program Files (x86)\XDM\xdm-app.exe`, UpgradeCode `3E462F34-…`, marker `HKLM\Software\Xtreme Download Manager\Installed2` | `HKCU\…\Run\XDM` = `"…xdm-app.exe" --background` (written by the MSI and by the app) | `HKCR\xdm-app` (MSI) |
| Windows (Store) | MSIX (per the old installer ReadMe) | MSIX startup task | MSIX manifest protocol |
| Linux | deb `xdman`, rpm/Arch `xdman_gtk`, `/opt/xdman`, `/usr/bin/xdman` | `~/.config/autostart/xdm-app.desktop` | `.desktop` `MimeType=x-scheme-handler/xdm-app` |

### 11.2 How XDM 9 takes over

- **Same names as XDM 8:** `AutoStart.kt` uses the same Run value name (`XDM`) and the same autostart file
  (`xdm-app.desktop`). At most one entry exists, and `sync()` repoints an old entry at XDM 9.
- **Windows MSI:** the same UpgradeCode removes XDM 8 in per-machine mode. The Store version is detected and the
  install blocked (§5.5).
- **Linux:** the deb keeps the name `xdman` (a normal upgrade); rpm/Arch replace `xdman_gtk`. `/opt/xdman` and
  `/usr/bin/xdman` keep their paths.
- **State:** XDM 9 uses `~/.xdm-app/`. It doesn't import XDM 8's settings or download list (out of scope).

---

## 12. Verification checklist before release

| # | Check | OS |
|---|---|---|
| 1 | Per-user install (`MSIINSTALLPERUSER=1`) shows no UAC prompt; the default install shows it and goes to Program Files | Windows |
| 2 | Installing over a running XDM (tray-only, and with the window open) shows no files-in-use dialog, and XDM is closed and relaunched | Windows |
| 3 | Upgrade from an XDM 8 MSI install **while XDM 8 is running**: XDM 8 closed and removed, no reboot prompt, `Run\XDM` points at XDM 9, port 8597 owned by XDM 9 | Windows |
| 4 | With the Store XDM 8 installed, the MSI blocks with the uninstall message | Windows |
| 5 | Installed jar mtime = 2020-01-01T00:00:00Z, and no `CDS: cannot re-pin` in the log, on a machine in another timezone | Windows |
| 6 | Full-JIT toggle on → restart → log says `Running full tiered`; toggle off → `Running C1 only` | all |
| 7 | ARM64 MSI: toggle on and locked; About shows Conscrypt not available; log says full tiered | Windows ARM64 |
| 8 | `codesign --verify --deep --strict` passes on the dmg's app; the downloaded app opens via Open Anyway (not "damaged") | macOS |
| 9 | `rpm -ql xdman` lists `/opt/xdman/lib/app` and `/opt/xdman/lib/runtime`; XDM starts from the rpm | Fedora |
| 10 | deb upgrade over XDM 8's `xdman`; rpm replaces `xdman_gtk`; exactly one menu entry and one `xdm-app://` handler | Ubuntu, Fedora |
| 11 | Highest `GLIBC_` version in the runtime and launcher ≤ the oldest supported distro | Linux |
| 12 | About shows the Conscrypt version on every C1 build | all |

---

## 13. Open decisions

1. Package name: `xdman` (proposed) or something else (§4.1).
2. Windows install folder: `Program Files\XDM` (proposed, XDM 8's name) or `Program Files\Xtreme Download Manager`.
3. XDM 8's Store package family name, for the detection in §5.5.
4. Whether to do the Conscrypt hardening in §3.5.

---

## Sources

- OpenJDK jpackage launcher: [AppLauncher.cpp](https://github.com/openjdk/jdk/blob/master/src/jdk.jpackage/share/native/applauncher/AppLauncher.cpp),
  [CfgFile.cpp](https://github.com/openjdk/jdk/blob/master/src/jdk.jpackage/share/native/applauncher/CfgFile.cpp),
  [WinLauncher.cpp](https://github.com/openjdk/jdk/blob/master/src/jdk.jpackage/windows/native/applauncher/WinLauncher.cpp),
  [MacLauncher.cpp](https://github.com/openjdk/jdk/blob/master/src/jdk.jpackage/macosx/native/applauncher/MacLauncher.cpp),
  [LinuxLauncherLib.cpp](https://github.com/openjdk/jdk/blob/master/src/jdk.jpackage/linux/native/libapplauncher/LinuxLauncherLib.cpp),
  [Package.cpp](https://github.com/openjdk/jdk/blob/master/src/jdk.jpackage/linux/native/libapplauncher/Package.cpp),
  [JDK-8341641](https://bugs.openjdk.org/browse/JDK-8341641)
- WiX: [Compiler.cs](https://github.com/wixtoolset/wix/blob/main/src/wix/WixToolset.Core/Compiler.cs),
  [caDecor.wxi](https://github.com/wixtoolset/wix/blob/main/src/ext/caDecor.wxi),
  [Compiler_Package.cs](https://github.com/wixtoolset/wix/blob/main/src/wix/WixToolset.Core/Compiler_Package.cs),
  [UtilCompiler.cs](https://github.com/wixtoolset/wix/blob/main/src/ext/Util/wixext/UtilCompiler.cs),
  [CloseApps.cpp](https://github.com/wixtoolset/wix/blob/main/src/ext/Util/ca/CloseApps.cpp),
  [UtilExtension_Platform.wxi](https://github.com/wixtoolset/wix/blob/main/src/ext/Util/wixlib/UtilExtension_Platform.wxi)
- Conscrypt: [releases](https://github.com/google/conscrypt/releases),
  [2.7.0 artifacts](https://repo1.maven.org/maven2/org/conscrypt/conscrypt-openjdk/2.7.0/),
  [NativeLibraryLoader.java](https://github.com/google/conscrypt/blob/master/openjdk/src/main/java/org/conscrypt/NativeLibraryLoader.java)
- nFPM: [configuration](https://nfpm.goreleaser.com/docs/configuration/),
  [nfpm.go](https://github.com/goreleaser/nfpm/blob/main/nfpm.go),
  [rpm/relations.go](https://github.com/goreleaser/nfpm/blob/main/rpm/relations.go)
- GitHub: [arm64 runners for public repos GA](https://github.blog/changelog/2025-08-07-arm64-hosted-runners-for-public-repositories-are-now-generally-available/)
- MSI: [ms-appinstaller disabled](https://learn.microsoft.com/en-us/windows/msix/app-installer/installing-windows10-apps-web),
  [MSI/EXE Store updates](https://learn.microsoft.com/en-us/windows/apps/publish/publish-your-app/msi/publish-update-to-your-app-on-store),
  [installed file timestamps](https://www.itninja.com/question/timestamp-of-file-installed)
- Signing: [SignPath Foundation terms](https://signpath.org/terms), [Azure Artifact Signing FAQ](https://learn.microsoft.com/en-us/azure/artifact-signing/faq),
  [Apple fee waivers](https://developer.apple.com/help/account/membership/fee-waivers/)
- [Homebrew Gatekeeper deprecation](https://github.com/orgs/Homebrew/discussions/6334)

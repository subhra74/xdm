#!/usr/bin/env bash
#
# Xtreme Download Manager - Linux one-liner installer.
#
#   curl -fsSL https://raw.githubusercontent.com/subhra74/xdm/master/packaging/linux/install.sh | bash
#
# Picks the native package for your distro (deb / rpm / Arch pkg) or falls back
# to the portable tarball, downloading it from the latest GitHub release.
# The script asks for your sudo password only when it actually installs.
#
# Options / environment:
#   --version <ver|tag>   install a specific version (default: latest release).
#                         Accepts a plain version (9.0.8) or a tag (v9.0.8).
#   --portable            skip native packages, install the tarball to /opt.
#   --uninstall           remove XDM (native package and/or portable files).
#   --yes, -y             assume yes for the package manager (non-interactive).
#   --dry-run             print what would be done, download nothing.
#   XDM_VERSION, XDM_REPO, XDM_PORTABLE=1, XDM_YES=1 work as well.
#
set -euo pipefail

REPO="${XDM_REPO:-subhra74/xdm}"
VERSION="${XDM_VERSION:-}"
PORTABLE="${XDM_PORTABLE:-0}"
ASSUME_YES="${XDM_YES:-0}"
ACTION="install"
DRY_RUN=0

usage() {
  awk 'NR<3{next} /^[^#]/ {exit} {print}' "$0" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) VERSION="$2"; shift 2 ;;
    --version=*) VERSION="${1#*=}"; shift ;;
    --portable) PORTABLE=1; shift ;;
    --uninstall) ACTION="uninstall"; shift ;;
    --yes|-y) ASSUME_YES=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option: $1 (see --help)" >&2; exit 1 ;;
  esac
done

need() {
  command -v "$1" >/dev/null 2>&1 || { echo "missing required tool: $1" >&2; exit 1; }
}
need curl
need tar

SUDO=""
[[ "$(id -u)" -eq 0 ]] || SUDO="sudo"

# ---- architecture -----------------------------------------------------------
case "$(uname -m)" in
  x86_64|amd64) DEB_ARCH=amd64; RPM_ARCH=x86_64; PKG_ARCH=x86_64; TGZ_ARCH=x64 ;;
  aarch64|arm64) DEB_ARCH=arm64; RPM_ARCH=aarch64; PKG_ARCH=aarch64; TGZ_ARCH=arm64 ;;
  *) echo "unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

# ---- package manager --------------------------------------------------------
PM=""
if command -v pacman >/dev/null 2>&1; then PM=pacman;
elif command -v apt-get >/dev/null 2>&1; then PM=apt;
elif command -v dnf >/dev/null 2>&1; then PM=dnf;
elif command -v yum >/dev/null 2>&1; then PM=yum;
elif command -v zypper >/dev/null 2>&1; then PM=zypper;
fi
[[ "$PORTABLE" == 1 ]] && PM=""

# ---- version ----------------------------------------------------------------
if [[ -z "$VERSION" ]]; then
  VERSION="$(curl -fsSL --retry 3 "https://api.github.com/repos/$REPO/releases/latest" \
    | grep -o '"tag_name": *"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
  [[ -n "$VERSION" ]] || { echo "could not determine the latest release (network or API limit?); pass --version explicitly" >&2; exit 1; }
fi
TAG="$VERSION"
# Plain versions (9.0.8) are tags as-is; a `v`-prefixed tag is used verbatim.
echo ">> XDM $TAG ($REPO)"

# ---- asset selection --------------------------------------------------------
# Names (see .github/workflows/binaries.yml): deb is xdm-app_<v>_<deb>.deb, rpm is
# xdm-app-<v>-1.<rpm>.rpm, Arch is xdm-app-<v>-1-<pkg>.pkg.tar.zst, portable is
# xdm-app-<v>-linux-<tgz>.tar.gz. Match by suffix so a pkgrel bump won't break us.
PATTERN=""
case "$PM" in
  pacman) PATTERN="-$PKG_ARCH\\.pkg\\.tar\\.zst$" ;;
  apt)    PATTERN="_${DEB_ARCH}\\.deb$" ;;
  dnf|yum|zypper) PATTERN="\\.$RPM_ARCH\\.rpm$" ;;
esac

ASSET=""
if [[ -n "$PATTERN" ]]; then
  ASSET="$(curl -fsSL --retry 3 "https://api.github.com/repos/$REPO/releases/tags/$TAG" \
    | grep -o '"name": *"[^"]*"' | cut -d'"' -f4 | grep -E -e "$PATTERN" | head -1 || true)"
  if [[ -z "$ASSET" ]]; then
    echo "!! no native package for $TAG on this system, falling back to portable" >&2
    PM=""
  fi
fi
if [[ -z "$PM" ]]; then
  ASSET="xdm-app-${TAG#v}-linux-$TGZ_ARCH.tar.gz"
fi
URL="https://github.com/$REPO/releases/download/$TAG/$ASSET"

# ---- uninstall --------------------------------------------------------------
if [[ "$ACTION" == "uninstall" ]]; then
  echo ">> removing XDM"
  [[ "$DRY_RUN" == 1 ]] && { echo "dry-run: uninstall $ASSET via $PM / portable cleanup"; exit 0; }
  if command -v pacman >/dev/null 2>&1 && pacman -Q xdm-app >/dev/null 2>&1; then
    $SUDO pacman -R --noconfirm xdm-app || $SUDO pacman -R xdm-app
  elif command -v dpkg >/dev/null 2>&1 && dpkg -l xdm-app >/dev/null 2>&1; then
    $SUDO apt-get remove -y xdm-app || $SUDO apt-get remove xdm-app
  elif command -v rpm >/dev/null 2>&1 && rpm -q xdm-app >/dev/null 2>&1; then
    if command -v dnf >/dev/null 2>&1; then $SUDO dnf remove -y xdm-app || $SUDO dnf remove xdm-app;
    else $SUDO rpm -e xdm-app; fi
  fi
  for f in /opt/xdm-app /usr/bin/xdm-app /usr/share/applications/xdm-app.desktop; do
    [[ -e "$f" || -L "$f" ]] && $SUDO rm -rf "$f"
  done
  echo ">> uninstalled (your downloads and settings in ~/.local/share/xdm-app are kept)"
  exit 0
fi

# ---- install ----------------------------------------------------------------
echo ">> package manager: ${PM:-portable tarball}"
echo ">> asset: $ASSET"
if [[ "$DRY_RUN" == 1 ]]; then
  echo "dry-run: would download $URL and install via ${PM:-portable}"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
echo ">> downloading $ASSET"
curl -fsSL --retry 3 -o "$TMP/$ASSET" "$URL"

case "$PM" in
  pacman)
    if [[ "$ASSUME_YES" == 1 ]]; then $SUDO pacman -U --noconfirm "$TMP/$ASSET";
    else $SUDO pacman -U "$TMP/$ASSET"; fi ;;
  apt)
    if [[ "$ASSUME_YES" == 1 ]]; then $SUDO apt-get install -y "$TMP/$ASSET";
    else $SUDO apt-get install "$TMP/$ASSET"; fi ;;
  dnf|yum)
    if [[ "$ASSUME_YES" == 1 ]]; then $SUDO "$PM" install -y "$TMP/$ASSET";
    else $SUDO "$PM" install "$TMP/$ASSET"; fi ;;
  zypper)
    if [[ "$ASSUME_YES" == 1 ]]; then $SUDO zypper --non-interactive install "$TMP/$ASSET";
    else $SUDO zypper install "$TMP/$ASSET"; fi ;;
  *)
    # Portable: same layout as the native packages (/opt/xdm-app + symlink),
    # desktop entry matching packaging/linux/xdm-app.desktop, icon from the bundle.
    echo ">> extracting to /opt/xdm-app"
    tar -xzf "$TMP/$ASSET" -C "$TMP"
    TOP="$(echo "$TMP"/xdm-app-*/)"
    $SUDO rm -rf /opt/xdm-app
    $SUDO mkdir -p /opt/xdm-app
    # shellcheck disable=SC2086
    $SUDO cp -a $TOP/bin $TOP/lib /opt/xdm-app/
    $SUDO ln -sf /opt/xdm-app/bin/xdm-app /usr/bin/xdm-app
    $SUDO tee /usr/share/applications/xdm-app.desktop >/dev/null <<'EOF'
[Desktop Entry]
Type=Application
Name=Xtreme Download Manager
GenericName=Download Manager
Comment=Download accelerator and video downloader
Exec=/opt/xdm-app/bin/xdm-app %U
Icon=xdm-app
Terminal=false
Categories=Network;FileTransfer;
Keywords=download;accelerator;video;hls;dash;
MimeType=x-scheme-handler/xdm-app;
StartupNotify=false
StartupWMClass=xdm-app-AppMain
EOF
    $SUDO mkdir -p /usr/share/icons/hicolor/256x256/apps
    $SUDO cp -f /opt/xdm-app/lib/xdm-app.png /usr/share/icons/hicolor/256x256/apps/xdm-app.png
    $SUDO update-desktop-database -q /usr/share/applications 2>/dev/null || true
    $SUDO gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor 2>/dev/null || true
    ;;
esac

echo ">> installed: run 'xdm-app' or pick Xtreme Download Manager from your app menu"

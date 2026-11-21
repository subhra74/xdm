#!/usr/bin/env bash
#
# Makes the .flatpak bundle from the app image (private-docs/docs/flatpak.md §6). Runs inside the
# Linux build image after the other packages (packaging/linux/package.sh), one architecture per run.
#
#   build-flatpak.sh <app image folder> <output .flatpak> <version>
#
# No flatpak-builder (it needs a privileged container): build-init, copy the image, build-finish,
# build-export to a local repo, build-bundle. The bundle has no --repo-url, so Flatpak never updates
# it by itself; only the runtime comes from Flathub, on first install.
#
set -euo pipefail

IMAGE="$1"
BUNDLE="$2"
VERSION="$3"

APP_ID=com.xtremedownloadmanager.XDM
# Keep in step with the runtime the Dockerfile installs.
RUNTIME_BRANCH="${FLATPAK_RUNTIME_BRANCH:-25.08}"
APP_BRANCH=stable
RUNTIME_REPO=https://dl.flathub.org/repo/flathub.flatpakrepo

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="$(dirname "$BUNDLE")/flatpak"
rm -rf "$WORK"
mkdir -p "$WORK"

# build-init wants an installed SDK, used only by `flatpak build`, which this script never runs: nothing is
# compiled. The Platform stands in for it, so the build image needs only the runtime.
flatpak build-init "$WORK/app" "$APP_ID" org.freedesktop.Platform org.freedesktop.Platform "$RUNTIME_BRANCH"

# The image as is: bin/xdm-app, lib/app, lib/runtime -> /app. No lib/app/.package (no dpkg/rpm here).
cp -a "$IMAGE"/. "$WORK/app/files/"

share="$WORK/app/files/share"
for size in 16 24 32 48 64 128 256 512; do
  install -Dm644 "$ROOT/xdm-app/src/main/resources/images/xdm-logo-$size.png" \
    "$share/icons/hicolor/${size}x${size}/apps/$APP_ID.png"
done
install -Dm644 "$HERE/$APP_ID.desktop" "$share/applications/$APP_ID.desktop"
mkdir -p "$share/metainfo"
sed -e "s|@VERSION@|$VERSION|" -e "s|@DATE@|$(date -u +%Y-%m-%d)|" \
  "$HERE/$APP_ID.metainfo.xml" > "$share/metainfo/$APP_ID.metainfo.xml"

# The AppStream catalog build-bundle embeds in the bundle: GNOME Software and Discover show the name, summary,
# licence, developer and icon from it when the .flatpak is opened (without it, only the app ID). flatpak-builder
# makes it with appstreamcli compose; here it is the metainfo component plus the cached icon and the bundle ref.
arch="$(flatpak --default-arch)"
mkdir -p "$share/app-info/xmls"
{
  echo '<?xml version="1.0" encoding="UTF-8"?>'
  echo "<components version=\"0.14\" origin=\"$APP_ID\">"
  sed -e '/^<?xml/d' -e '/^<!--.*-->$/d' \
      -e "s|</component>|  <icon type=\"cached\" width=\"64\" height=\"64\">$APP_ID.png</icon>\n  <icon type=\"cached\" width=\"128\" height=\"128\">$APP_ID.png</icon>\n  <bundle type=\"flatpak\">app/$APP_ID/$arch/$APP_BRANCH</bundle>\n</component>|" \
      "$share/metainfo/$APP_ID.metainfo.xml"
  echo '</components>'
} | gzip -9n > "$share/app-info/xmls/$APP_ID.xml.gz"
for size in 64 128; do
  install -Dm644 "$ROOT/xdm-app/src/main/resources/images/xdm-logo-$size.png" \
    "$share/app-info/icons/flatpak/${size}x${size}/$APP_ID.png"
done

mapfile -t finish_args < <(grep -v -e '^#' -e '^[[:space:]]*$' "$HERE/finish-args")
flatpak build-finish "$WORK/app" --command=xdm-app "${finish_args[@]}"

# build-export checks each exported icon with flatpak-validate-icon --sandbox, which needs bwrap, and an
# unprivileged container can't make bwrap's namespaces. The icons are ours, so check them without the
# sandbox: FLATPAK_VALIDATE_ICON names the validator, and this one drops --sandbox and runs the real one.
if [[ -x /usr/libexec/flatpak-validate-icon ]]; then
  cat > "$WORK/validate-icon" <<'SH'
#!/bin/sh
for a; do shift; [ "$a" = --sandbox ] || set -- "$@" "$a"; done
exec /usr/libexec/flatpak-validate-icon "$@"
SH
  chmod +x "$WORK/validate-icon"
  export FLATPAK_VALIDATE_ICON="$WORK/validate-icon"
fi
flatpak build-export "$WORK/repo" "$WORK/app" "$APP_BRANCH"
flatpak build-bundle --runtime-repo="$RUNTIME_REPO" "$WORK/repo" "$BUNDLE" "$APP_ID" "$APP_BRANCH"
rm -rf "$WORK"

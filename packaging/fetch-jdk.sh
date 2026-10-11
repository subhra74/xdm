#!/usr/bin/env bash
#
# Downloads the Liberica JDK pinned in packaging/jdk.properties for macOS, checks BellSoft's SHA-1 and
# unpacks it under build/jdks/. Prints the JDK home (the JAVA_HOME to build with) as the last line.
#
#   packaging/fetch-jdk.sh                # this Mac's architecture
#   packaging/fetch-jdk.sh mac-x64        # or name the target: mac-arm64 | mac-x64
#   export JAVA_HOME="$(packaging/fetch-jdk.sh | tail -1)"; export PATH="$JAVA_HOME/bin:$PATH"
#
# Already unpacked: nothing is downloaded. Windows uses setup-windows.ps1 -InstallJdk, Linux the Docker
# image (packaging/linux/Dockerfile); both read the same file.
#
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pin() { sed -n "s/^$1=//p" "$PROJECT_ROOT/packaging/jdk.properties"; }

case "${1:-}" in
  "") case "$(uname -m)" in arm64|aarch64) TARGET=mac-arm64 ;; *) TARGET=mac-x64 ;; esac ;;
  mac-arm64|mac-x64) TARGET="$1" ;;
  -h|--help) sed -n '3,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "Unknown target: $1 (mac-arm64 or mac-x64)" >&2; exit 1 ;;
esac

VERSION="$(pin liberica.version)"
URL="$(pin "liberica.$TARGET.url")"
SHA1="$(pin "liberica.$TARGET.sha1")"
[[ -n "$URL" && -n "$SHA1" ]] || { echo "packaging/jdk.properties has no liberica.$TARGET.url/sha1" >&2; exit 1; }

DEST="$PROJECT_ROOT/build/jdks/liberica-${VERSION/+/_}-$TARGET"
# The JDK home holds bin/java and release: the tarball's top folder (jdk-<ver>.jdk), or its Contents/Home.
home() { find "$DEST" -maxdepth 4 -type f -path '*/bin/java' 2>/dev/null | head -1 | xargs -I{} dirname {} | xargs -I{} dirname {}; }
if [[ -n "$(home)" ]]; then
  echo ">> pinned Liberica $VERSION ($TARGET) already in $DEST" >&2
  home
  exit 0
fi

mkdir -p "$PROJECT_ROOT/build/jdks"
TMP="$(mktemp -d "$PROJECT_ROOT/build/jdks/.fetch-XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
echo ">> downloading $URL" >&2
curl -fsSL --retry 3 -o "$TMP/jdk.tar.gz" "$URL"
echo "$SHA1  $TMP/jdk.tar.gz" | shasum -a 1 -c - >&2
mkdir "$TMP/unpacked"
tar -xzf "$TMP/jdk.tar.gz" -C "$TMP/unpacked"
mv "$TMP/unpacked" "$DEST"
echo ">> unpacked to $DEST" >&2
home

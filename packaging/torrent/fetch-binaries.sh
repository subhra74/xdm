#!/usr/bin/env bash
# Puts the native torrent library for a platform into <dest>/<platform>/ (default ~/.cache/xdm-torrent/bin),
# from the release pinned in binaries.lock (hash checked) or, with --local, from this machine's own builds.
# The builds come from the separate libtorrent-builds repo (build-unix.sh, build-linux-docker.sh).
#
#   ./fetch-binaries.sh [--platform P|all] [--local] [--dest DIR]
#
#   --platform  windows-x64 | windows-arm64 | macos-arm64 | macos-x64 | linux-x64 | linux-arm64 | all;
#               default this machine's
#   --local     copy from build output instead of downloading: ~/.cache/xdm-torrent/out/<P>/bin (build-unix.sh,
#               or Windows output copied there) and ~/.cache/xdm-torrent-linux/out/<P>/bin (build-linux-docker.sh)
# Prints the path of each library it put in place.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
dest="$HOME/.cache/xdm-torrent/bin"
platforms=""
local_build=0
while [ $# -gt 0 ]; do
    case "$1" in
        --platform) platforms="$2"; shift 2 ;;
        --local) local_build=1; shift ;;
        --dest) dest="$2"; shift 2 ;;
        *) echo "unknown option $1" >&2; exit 2 ;;
    esac
done

host_platform() {
    local os arch
    case "$(uname -s)" in Darwin) os=macos ;; Linux) os=linux ;; MINGW*|MSYS*|CYGWIN*) os=windows ;; *) os=unknown ;; esac
    case "$(uname -m)" in arm64|aarch64) arch=arm64 ;; *) arch=x64 ;; esac
    echo "$os-$arch"
}
lib_name() {
    case "$1" in windows-*) echo xdmtorrent.dll ;; macos-*) echo libxdmtorrent.dylib ;; *) echo libxdmtorrent.so ;; esac
}
sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

[ -z "$platforms" ] && platforms="$(host_platform)"
[ "$platforms" = all ] && platforms="windows-x64 windows-arm64 macos-arm64 macos-x64 linux-x64 linux-arm64"

status=0
for p in $platforms; do
    file="$(lib_name "$p")"
    mkdir -p "$dest/$p"
    target="$dest/$p/$file"
    if [ "$local_build" = 1 ]; then
        src=""
        for c in "$HOME/.cache/xdm-torrent/out/$p/bin/$file" "$HOME/.cache/xdm-torrent-linux/out/$p/bin/$file"; do
            [ -f "$c" ] && { src="$c"; break; }
        done
        if [ -z "$src" ]; then echo "no local build for $p" >&2; status=1; continue; fi
        cp "$src" "$target.part" && mv "$target.part" "$target"
        echo "$target"
        continue
    fi
    line="$(grep -E "^$p\|" "$here/binaries.lock" || true)"
    if [ -z "$line" ]; then echo "binaries.lock has no entry for $p (use --local)" >&2; status=1; continue; fi
    IFS='|' read -r _ lfile url hash <<<"$line"
    [ "$lfile" = "$file" ] || { echo "binaries.lock names $lfile for $p, expected $file" >&2; status=1; continue; }
    if [ ! -f "$target" ] || [ "$(sha256 "$target")" != "$hash" ]; then
        curl -fL --retry 3 -o "$target.part" "$url"
        got="$(sha256 "$target.part")"
        if [ "$got" != "$hash" ]; then rm -f "$target.part"; echo "sha256 mismatch for $p: $got" >&2; status=1; continue; fi
        mv "$target.part" "$target"
    fi
    echo "$target"
done
exit $status

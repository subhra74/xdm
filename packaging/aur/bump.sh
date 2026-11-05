#!/usr/bin/env bash
#
# Bump packaging/aur/PKGBUILD to a new upstream release and regenerate .SRCINFO.
#
#   packaging/aur/bump.sh <pkgver> [<tag>]
#
# <pkgver> is the version (e.g. 9.0.9); <tag> is the GitHub release tag
# (defaults to <pkgver>). Downloads both portable tarballs, updates pkgver,
# pkgrel and the checksums, then refreshes .SRCINFO (needs makepkg).
#
set -euo pipefail
cd "$(dirname "$0")"

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <pkgver> [<tag>]" >&2
  exit 1
fi
PKGVER="$1"
TAG="${2:-$1}"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for arch in x64 arm64; do
  url="https://github.com/subhra74/xdm/releases/download/$TAG/xdm-app-$PKGVER-linux-$arch.tar.gz"
  echo ">> $url"
  curl -fsSL --retry 3 -o "$TMP/$arch.tar.gz" "$url"
done

X64_SUM="$(sha256sum "$TMP/x64.tar.gz" | awk '{print $1}')"
ARM64_SUM="$(sha256sum "$TMP/arm64.tar.gz" | awk '{print $1}')"

sed -i -e "s/^pkgver=.*/pkgver=$PKGVER/" \
       -e "s/^pkgrel=.*/pkgrel=1/" \
       -e "s/^\(_tag=\).*/\1\"$TAG\"/" \
  PKGBUILD
python3 - "$X64_SUM" "$ARM64_SUM" <<'EOF'
import re, sys
x64, arm64 = sys.argv[1], sys.argv[2]
p = "PKGBUILD"
s = open(p).read()
s = re.sub(r"(?<=sha256sums_x86_64=\(')[0-9a-f]+(?='\))", x64, s)
s = re.sub(r"(?<=sha256sums_aarch64=\(')[0-9a-f]+(?='\))", arm64, s)
open(p, "w").write(s)
EOF

if command -v makepkg >/dev/null 2>&1; then
  makepkg --printsrcinfo > .SRCINFO
  echo ">> .SRCINFO regenerated"
else
  echo "!! makepkg not found: update .SRCINFO by hand (makepkg --printsrcinfo > .SRCINFO)" >&2
fi

grep -n "^pkgver=\|^pkgrel=\|^_tag=\|sha256sums_x86_64\|sha256sums_aarch64" PKGBUILD

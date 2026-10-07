#!/usr/bin/env bash
#
# Runs inside the Linux build image (packaging/build-linux.sh): the app image, then the deb, rpm,
# Arch package and tar.gz made from it. Output: build/linux-<arch>/.
#
set -euo pipefail
cd "$(dirname "$0")/../.."

case "$(uname -m)" in
  aarch64|arm64) ARCH=arm64; PKG_ARCH=arm64 ;;
  x86_64|amd64)  ARCH=x64;   PKG_ARCH=amd64 ;;
  *) echo "unsupported machine: $(uname -m)" >&2; exit 1 ;;
esac
OUT="build/linux-$ARCH"
rm -rf "$OUT"
mkdir -p "$OUT"

# The app image, with the AppCDS archive when packaging/cds/linux-<arch>.classlist exists.
packaging/build-bundle.sh -s -t app-image -o "$OUT/image"
IMAGE="$OUT/image/xdm-app"
[[ -x "$IMAGE/bin/xdm-app" ]] || { echo "no app image at $IMAGE" >&2; exit 1; }

VERSION="$(sed -n 's/.*"currentVersion"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
  xdm-app/src/main/resources/app-version.json | head -1)"

# Every shared library of the image must resolve against the packages' dependencies (nfpm.yaml);
# ldd only sees what this build image has installed, which is that list (see the Dockerfile).
# Libraries the runtime ships itself (libjvm, libjawt) resolve at run time and are skipped.
shipped="$(find "$IMAGE" -name '*.so*' -exec basename {} \; | sort -u)"
missing="$(find "$IMAGE" -name '*.so' -exec ldd {} + 2>/dev/null | awk '/not found/ {print $1}' | sort -u \
  | grep -vxF -f <(echo "$shipped") || true)"
if [[ -n "$missing" ]]; then
  echo "!! libraries the image needs but no dependency in nfpm.yaml provides:" >&2
  echo "$missing" | sed 's/^/!!   /' >&2
fi

# nfpm doesn't expand variables in every field (contents paths included), so fill them in here.
sed -e "s|\${ARCH}|$PKG_ARCH|g" -e "s|\${VERSION}|$VERSION|g" -e "s|\${IMAGE}|$IMAGE|g" \
  packaging/linux/nfpm.yaml > "$OUT/nfpm.yaml"
for packager in deb rpm archlinux; do
  nfpm package -f "$OUT/nfpm.yaml" -p "$packager" -t "$OUT/"
done

# The tar.gz is the plain image (no .package: per-user .cfg overrides need dpkg/rpm, PACKAGING.md 7.3).
tar -C "$OUT/image" -czf "$OUT/xdm-app-$VERSION-linux-$ARCH.tar.gz" \
  --transform "s,^xdm-app,xdm-app-$VERSION," xdm-app

echo
echo "Output (build/linux-$ARCH):"
ls -l "$OUT" | grep -v '^total' | grep -v ' image$'

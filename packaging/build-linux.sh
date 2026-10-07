#!/usr/bin/env bash
#
# Builds the Linux app image and packages (deb, rpm, Arch, tar.gz) in Docker, on macOS or Linux.
# The jar is built here; everything Linux-specific runs in packaging/linux/Dockerfile's image.
#
#   packaging/build-linux.sh                  # mvn package, then the host's architecture
#   packaging/build-linux.sh --arch x64       # the other one (emulated on Apple silicon: slow)
#   packaging/build-linux.sh -s               # reuse xdm-app/target/xdm-app.jar
#
# Output: build/linux-<arch>/. The class list (packaging/cds/linux-<arch>.classlist) is recorded on a
# Linux desktop, not here: build-bundle.sh --record-server there (docs/class-list-recording.md).
#
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

case "$(uname -m)" in arm64|aarch64) ARCH=arm64 ;; *) ARCH=x64 ;; esac
SKIP_MAVEN=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --arch) ARCH="$2"; shift 2 ;;
    -s|--skip-build) SKIP_MAVEN=1; shift ;;
    -h|--help) sed -n '3,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done
case "$ARCH" in
  arm64) PLATFORM=linux/arm64 ;;
  x64)   PLATFORM=linux/amd64 ;;
  *) echo "--arch must be arm64 or x64" >&2; exit 1 ;;
esac

if [[ $SKIP_MAVEN -eq 0 ]]; then
  echo ">> mvn package"
  mvn -q -pl xdm-app -am package -DskipTests
fi
[[ -f xdm-app/target/xdm-app.jar ]] || { echo "Missing xdm-app/target/xdm-app.jar - run without -s" >&2; exit 1; }

IMAGE="xdm-linux-build:$ARCH"
echo ">> build image $IMAGE ($PLATFORM)"
docker build -q --platform "$PLATFORM" -t "$IMAGE" packaging/linux >/dev/null

echo ">> packaging in $IMAGE"
docker run --rm --platform "$PLATFORM" -v "$PROJECT_ROOT:/src" -w /src "$IMAGE" packaging/linux/package.sh

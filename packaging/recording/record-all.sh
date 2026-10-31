#!/usr/bin/env bash
#
# Records both class lists, packaging/cds/liberica.classlist and packaging/cds/jbr.classlist, in one go:
# starts RecordServer in the background, builds the jar, runs record-liberica.sh and record-jbr.sh against
# the server and stops it again. See docs/class-list-recording.md section 0.
#
#   packaging/recording/record-all.sh [--no-build] [--only liberica|jbr]
#
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$PROJECT_ROOT"
die() { echo "!! $*" >&2; exit 1; }

usage() {
  cat <<EOF
Usage: $(basename "$0") [--no-build] [--only liberica|jbr]

Records both AppCDS class lists on the pinned JDKs with the scripted session. macOS only.
Starts RecordServer on port 8780 (proxy on 8781) for the run and stops it afterwards.

  --no-build  Use xdm-app/target/xdm-app.jar as it is (built only when missing)
  --only V    Record just one list: liberica or jbr
EOF
}

BUILD=1
VENDORS=(liberica jbr)
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-build) BUILD=0; shift ;;
    --only)
      [[ "${2:-}" == liberica || "${2:-}" == jbr ]] || { usage >&2; exit 1; }
      VENDORS=("$2"); shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 1 ;;
  esac
done

[[ "$(uname -s)" == Darwin ]] || die "Class lists are recorded on the Mac only (docs/class-list-recording.md)"

PORT=8780
SERVER="http://127.0.0.1:$PORT"
listening() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }
listening 8597 && die "port 8597 is in use: quit the running XDM first"
for p in $PORT $((PORT + 1)); do
  listening "$p" && die "port $p is in use: stop the RecordServer already running (or whatever holds it)"
done

# ---- the jar, once for both lists ---------------------------------------------
JAR="xdm-app/target/xdm-app.jar"
if [[ $BUILD -eq 1 || ! -f "$JAR" ]]; then
  echo ">> building $JAR"
  mvn -q -pl xdm-app -am package
fi

# ---- the record server, for this run only ---------------------------------------
LOG="build/record-server.log"
mkdir -p build
echo ">> starting RecordServer on $SERVER (log: $LOG)"
java packaging/recording/RecordServer.java --port "$PORT" > "$LOG" 2>&1 &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true; wait "$SERVER_PID" 2>/dev/null || true' EXIT

# The first start makes the HLS/DASH media with ffmpeg, which takes a while.
for ((i = 0; i < 600; i++)); do
  curl -fsS -m 2 -o /dev/null "$SERVER/session.properties" 2>/dev/null && break
  kill -0 "$SERVER_PID" 2>/dev/null || { tail -20 "$LOG" >&2; die "RecordServer exited (log above, $LOG)"; }
  sleep 1
done
curl -fsS -m 2 -o /dev/null "$SERVER/session.properties" 2>/dev/null ||
  die "RecordServer didn't answer within 10 minutes ($LOG)"

# ---- record ----------------------------------------------------------------------
for vendor in "${VENDORS[@]}"; do
  echo
  echo "==== $vendor ===="
  "packaging/recording/record-$vendor.sh" "$SERVER"
done

echo
echo ">> done; review and commit:"
git --no-pager diff --stat -- packaging/cds/

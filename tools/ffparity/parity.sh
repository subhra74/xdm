#!/bin/bash
# Builds xdm-core and the Mux harness (tools/transmux-corpus/Mux.java), then runs parity.py.
#   ./parity.sh [case ...]              cases made by cases.sh (or folders of your own)
#   ./parity.sh --corpus [variant ...]  the transmux corpus made by ../transmux-corpus/gen.sh
#   XDM_PARITY_CASES, XDM_PARITY_OUT, XDM_CORPUS_DIR, XDM_JAVA (JDK 25), XDM_FFMPEG: see parity.py
set -e
S=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$S/../.." && pwd)
export XDM_PARITY_OUT=${XDM_PARITY_OUT:-${TMPDIR:-/tmp}/xdm-parity-out}
JAVA=${XDM_JAVA:-java}
export XDM_JAVA=$JAVA
mkdir -p "$XDM_PARITY_OUT/harness"
(cd "$ROOT" && mvn -q -pl xdm-core -am compile && \
  mvn -q -pl xdm-core dependency:build-classpath -Dmdep.outputFile="$XDM_PARITY_OUT/deps.txt")
CP="$ROOT/xdm-core/target/classes:$(cat "$XDM_PARITY_OUT/deps.txt")"
"$(dirname "$JAVA")/javac" -cp "$CP" -d "$XDM_PARITY_OUT/harness" "$ROOT/tools/transmux-corpus/Mux.java" 2>/dev/null || \
  javac -cp "$CP" -d "$XDM_PARITY_OUT/harness" "$ROOT/tools/transmux-corpus/Mux.java"
export XDM_CHECK_CP="$XDM_PARITY_OUT/harness:$CP"
# The AVFoundation checker for --native (macOS only).
if [ "$(uname)" = Darwin ] && [ "$S/native/avf_check.swift" -nt "$XDM_PARITY_OUT/avf_check" ]; then
  swiftc -O -o "$XDM_PARITY_OUT/avf_check" "$S/native/avf_check.swift" 2>/dev/null
fi
python3 -I "$S/parity.py" "$@"

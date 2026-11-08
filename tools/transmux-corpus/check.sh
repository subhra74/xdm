#!/bin/bash
# Builds xdm-core and the Mux harness, then runs check.py over the corpus made by gen.sh.
#   ./check.sh [variant ...]
#   XDM_CORPUS_DIR  corpus (default: $TMPDIR/xdm-transmux-corpus)
#   XDM_CHECK_OUT   results (default: $TMPDIR/xdm-transmux-check)
#   XDM_JAVA        java from JDK 25 (default: java on PATH)
set -e
S=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$S/../.." && pwd)
export XDM_CORPUS_DIR=${XDM_CORPUS_DIR:-${TMPDIR:-/tmp}/xdm-transmux-corpus}
export XDM_CHECK_OUT=${XDM_CHECK_OUT:-${TMPDIR:-/tmp}/xdm-transmux-check}
JAVA=${XDM_JAVA:-java}
export XDM_JAVA=$JAVA
[ -d "$XDM_CORPUS_DIR" ] || { echo "no corpus in $XDM_CORPUS_DIR: run gen.sh first" >&2; exit 2; }

mkdir -p "$XDM_CHECK_OUT/harness"
(cd "$ROOT" && mvn -q -pl xdm-core -am compile && \
  mvn -q -pl xdm-core dependency:build-classpath -Dmdep.outputFile="$XDM_CHECK_OUT/deps.txt")
CP="$ROOT/xdm-core/target/classes:$(cat "$XDM_CHECK_OUT/deps.txt")"
"$(dirname "$JAVA")/javac" -cp "$CP" -d "$XDM_CHECK_OUT/harness" "$S/Mux.java" 2>/dev/null || \
  javac -cp "$CP" -d "$XDM_CHECK_OUT/harness" "$S/Mux.java"
export XDM_CHECK_CP="$XDM_CHECK_OUT/harness:$CP"
python3 -I "$S/check.py" "$@"

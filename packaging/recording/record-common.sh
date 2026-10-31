# Shared by record-liberica.sh and record-jbr.sh (docs/class-list-recording.md section 3.1); not run on its own.
#
# The caller sets RECORD_VENDOR (liberica | jbr) and sources this file. It selects the pinned JDK for this
# process only, checks it, the port and the record server, builds the jar when needed, records with
# build-bundle.sh --record-only and checks the list's stamp.

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$PROJECT_ROOT"
pin() { sed -n "s/^$1=//p" "$PROJECT_ROOT/packaging/jdk.properties"; }
die() { echo "!! $*" >&2; exit 1; }

case "$RECORD_VENDOR" in
  liberica) LIST="packaging/cds/liberica.classlist" ;;
  jbr)      LIST="packaging/cds/jbr.classlist" ;;
  *)        die "RECORD_VENDOR must be liberica or jbr" ;;
esac

usage() {
  cat <<EOF
Usage: $(basename "$0") [--build] [SERVER_URL]

Records $LIST on the pinned $RECORD_VENDOR JDK with the scripted session
(packaging/build-bundle.sh --record-only). macOS only.

  SERVER_URL  The running RecordServer (default http://127.0.0.1:8780)
  --build     Rebuild xdm-app/target/xdm-app.jar first (otherwise only when it is missing)
EOF
}

SERVER="http://127.0.0.1:8780"
BUILD=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --build) BUILD=1; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) usage >&2; exit 1 ;;
    *) SERVER="${1%/}"; shift ;;
  esac
done

[[ "$(uname -s)" == Darwin ]] || die "Class lists are recorded on the Mac only (docs/class-list-recording.md)"

# ---- the pinned JDK, for this process only ------------------------------------
case "$(uname -m)" in arm64|aarch64) ARCH=arm64 ;; *) ARCH=x64 ;; esac
if [[ "$RECORD_VENDOR" == jbr ]]; then TARGET="jbr-mac-$ARCH"; else TARGET="mac-$ARCH"; fi
JAVA_HOME="$(packaging/fetch-jdk.sh "$TARGET" | tail -1)"
[[ -x "$JAVA_HOME/bin/java" ]] || die "packaging/fetch-jdk.sh $TARGET gave no JDK home ($JAVA_HOME)"
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"

prop() { java -XshowSettings:properties -version 2>&1 | sed -n "s/^ *$1 = //p" | head -1; }
VENDOR="$(prop 'java\.vendor')"
RUNTIME="$(prop 'java\.runtime\.version')"
if [[ "$RECORD_VENDOR" == jbr ]]; then
  [[ "$VENDOR" == "JetBrains s.r.o." && "$RUNTIME" == "$(pin jbr.version)+"*"-$(pin jbr.build)" ]] ||
    die "$JAVA_HOME is $VENDOR $RUNTIME, not the pinned JBR $(pin jbr.version) $(pin jbr.build)"
else
  [[ "$VENDOR" == "BellSoft" && "$RUNTIME" == "$(pin liberica.runtime)" ]] ||
    die "$JAVA_HOME is $VENDOR $RUNTIME, not the pinned BellSoft Liberica $(pin liberica.runtime)"
fi
STAMP="# xdm-jdk: $VENDOR $RUNTIME"
echo ">> recording with $VENDOR $RUNTIME ($JAVA_HOME)"

# ---- what the session needs ----------------------------------------------------
if lsof -nP -iTCP:8597 -sTCP:LISTEN >/dev/null 2>&1; then
  die "port 8597 is in use: quit the running XDM first"
fi
curl -fsS -m 5 -o /dev/null "$SERVER/session.properties" ||
  die "no record server at $SERVER: start it in another shell with  java packaging/recording/RecordServer.java"

JAR="xdm-app/target/xdm-app.jar"
if [[ $BUILD -eq 1 || ! -f "$JAR" ]]; then
  echo ">> building $JAR"
  mvn -q -pl xdm-app -am package
fi

# ---- record --------------------------------------------------------------------
BEFORE="$(stat -f %m "$LIST" 2>/dev/null || echo 0)"
packaging/build-bundle.sh -s --record-only --record-server "$SERVER"

[[ -f "$LIST" && "$(stat -f %m "$LIST")" -gt "$BEFORE" ]] || die "$LIST was not rewritten"
[[ "$(head -1 "$LIST")" == "$STAMP" ]] || die "$LIST starts with '$(head -1 "$LIST")', expected '$STAMP'"
echo ">> $LIST: $(grep -vc '^[#@]' "$LIST") classes, $STAMP"

#!/usr/bin/env bash
# Runs the real XDM app (the jar, with a throwaway profile) against server.py and checks two downloads:
#   1. limit.bin   - the server allows 3 connections and answers 403 beyond that: must finish, not "link expired"
#   2. signed.bin  - a signed URL that expires mid-transfer, sent with its source link: must refresh and finish
# macOS and Linux. See README.md. Exit status 0 when both pass.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
JAR="${XDM_JAR:-$ROOT/xdm-app/target/xdm-app.jar}"
SERVER_PORT="${SERVER_PORT:-18597}"
SIG_TTL="${SIG_TTL:-2}"
TIMEOUT="${TIMEOUT:-120}"
APP_PORT=8597
BASE="http://127.0.0.1:$SERVER_PORT"

die() { echo "FAIL: $*" >&2; exit 1; }
[ -f "$JAR" ] || die "no jar at $JAR - build it first: mvn -q -pl xdm-app -am package -DskipTests"
command -v python3 >/dev/null || die "python3 is needed for the server"
if curl -s -o /dev/null --max-time 2 "http://127.0.0.1:$APP_PORT/sync"; then
    die "something already listens on $APP_PORT (a running XDM?): quit it first"
fi

if command -v sha256sum >/dev/null; then sha() { sha256sum "$1" | cut -d' ' -f1; }; else sha() { shasum -a 256 "$1" | cut -d' ' -f1; }; fi

# A start that is not the profile's first one refreshes the user's login entry so that it points at this jar.
case "$(uname)" in
    Darwin) LOGIN_ITEM="$HOME/Library/LaunchAgents/app.xdm.autostart.plist" ;;
    *) LOGIN_ITEM="$HOME/.config/autostart/xdm-app.desktop" ;;
esac

TMP_ROOT="${TMPDIR:-/tmp}"
WORK="$(mktemp -d "${TMP_ROOT%/}/xdm-link-e2e.XXXXXX")"
DATA="$WORK/data"
OUT="$WORK/out"
mkdir -p "$DATA" "$OUT"
[ -f "$LOGIN_ITEM" ] && cp -p "$LOGIN_ITEM" "$WORK/login-item.bak"

SERVER_PID=""
APP_PID=""
cleanup() {
    [ -n "$APP_PID" ] && kill "$APP_PID" 2>/dev/null && wait "$APP_PID" 2>/dev/null || true
    [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null && wait "$SERVER_PID" 2>/dev/null || true
    if [ -f "$WORK/login-item.bak" ] && ! cmp -s "$WORK/login-item.bak" "$LOGIN_ITEM"; then
        cp -p "$WORK/login-item.bak" "$LOGIN_ITEM"
        echo "restored the login entry $LOGIN_ITEM"
    fi
    echo "logs and files kept in $WORK"
}
trap cleanup EXIT

python3 -I "$HERE/server.py" "$SERVER_PORT" "$SIG_TTL" > "$WORK/server.log" 2>&1 &
SERVER_PID=$!
curl -s -o /dev/null --retry 10 --retry-connrefused --retry-delay 1 "$BASE/stats" || die "server did not start"
read -r _ _ _ SIZE _ EXPECTED < "$WORK/server.log"

java -cp "$JAR" "$HERE/MakeConfig.java" "$DATA" "$OUT" > /dev/null
java -Dxdm.data.dir="$DATA" -jar "$JAR" > "$WORK/app.out" 2>&1 &
APP_PID=$!
curl -s -o /dev/null --retry 30 --retry-connrefused --retry-delay 1 "http://127.0.0.1:$APP_PORT/sync" || die "the app did not start"

# What the extension posts to /download. $1 url, $2 file name, $3 extra JSON fields.
post() {
    curl -s -o /dev/null -w "%{http_code}" -X POST "http://127.0.0.1:$APP_PORT/download" \
        -H "Content-Type: application/json" \
        -d "{\"url\":\"$1\",\"cookie\":\"cdn=1\",\"filename\":\"$2\",\"fileSize\":$SIZE,\"mimeType\":\"application/octet-stream\",
             \"requestHeaders\":{\"User-Agent\":[\"Mozilla/5.0\"],\"Referer\":[\"$BASE/page\"]},
             \"responseHeaders\":{\"Content-Length\":[\"$SIZE\"],\"Content-Type\":[\"application/octet-stream\"]},
             \"tabUrl\":\"$BASE/page\",\"tabId\":\"1\"$3}"
}

# Waits until $1 complete files are in the out folder and no part file is left.
wait_files() {
    local deadline=$(( $(date +%s) + TIMEOUT ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        local done
        done=$(find "$OUT" -maxdepth 1 -type f ! -name '.*' -size "${SIZE}c" | wc -l | tr -d ' ')
        if [ "$done" -ge "$1" ] && [ -z "$(find "$OUT" -maxdepth 1 -name '*.xdm-part')" ]; then return 0; fi
        sleep 1
    done
    return 1
}

stat_of() { curl -s "$BASE/stats" | python3 -I -c "import json,sys; print(json.load(sys.stdin)['$1'])"; }
log_has() { grep -q "$1" "$DATA"/logs/*.log "$WORK/app.out" 2>/dev/null; }

FAILED=0
check() { if eval "$2"; then echo "  ok   $1"; else echo "  FAIL $1"; FAILED=1; fi; }

echo "1. connection limit (3 connections, 403 beyond)"
[ "$(post "$BASE/limit.bin" limit.bin "")" = 200 ] || die "the app refused the /download message"
wait_files 1 || echo "  (timed out after ${TIMEOUT}s)"
check "limit.bin downloaded intact" '[ -f "$OUT/limit.bin" ] && [ "$(sha "$OUT/limit.bin")" = "$EXPECTED" ]'
check "the server refused extra connections" '[ "$(stat_of limit_403)" -gt 0 ]'
check "never more than 3 connections at once" '[ "$(stat_of limit_peak)" -le 3 ]'
check "busy refusals backed off" 'log_has "server limit, backing off"'

echo "2. signed URL expiring after ${SIG_TTL}s, refreshed from its source link"
# The browser's side: it followed the site's link to a freshly signed URL (this first /link request
# carries no cookie, so the cookie check below skips it).
SIGNED="$(curl -s -o /dev/null -w "%{redirect_url}" "$BASE/link")"
[ "$(post "$SIGNED" signed.bin ",\"sourceUrl\":\"$BASE/link\",\"sourceCookie\":\"site=1\"")" = 200 ] \
    || die "the app refused the /download message"
wait_files 2 || echo "  (timed out after ${TIMEOUT}s)"
SIGNED_FILE="$(find "$OUT" -maxdepth 1 -type f -name 'signed*' | head -1)"
check "signed file downloaded intact" '[ -n "$SIGNED_FILE" ] && [ "$(sha "$SIGNED_FILE")" = "$EXPECTED" ]'
check "the signed URL expired on the download" '[ "$(stat_of expired_403)" -gt 0 ]'
check "XDM asked the source link again" '[ "$(stat_of link_hits)" -ge 2 ] && log_has "asking its source link for a fresh one"'
check "it resumed on the fresh link" 'log_has "fresh link from its source, resuming"'
check "only the source host cookie went to the link" \
    '[ "$(curl -s "$BASE/stats" | python3 -I -c "import json,sys; c=json.load(sys.stdin)[\"link_cookies\"][1:]; print(c and all(x == \"site=1\" for x in c))")" = True ]'

if [ "$FAILED" = 0 ]; then echo "PASS"; else echo "FAIL (see $WORK)"; fi
exit "$FAILED"

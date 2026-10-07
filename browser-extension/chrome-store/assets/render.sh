#!/usr/bin/env bash
# Renders the Chrome Web Store images into chrome-store/assets/out/:
#   screenshot-1-popup.jpg, screenshot-2-bulk.jpg, screenshot-3-app.jpg   1280x800
#   promo-small.jpg 440x280, promo-marquee.jpg 1400x560
#
# The popup and bulk-picker shots are the extension's real pages, with chrome.* replaced by stub.js
# (demo data), served over a local HTTP server (bulk.js is an ES module, which file:// won't load)
# and captured with headless Chrome. JPEG, because the store wants no alpha channel.
#
# macOS (uses sips); needs Google Chrome and python3.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
EXT="$HERE/../../chrome-extension"
APP_SHOT="$HERE/../../../screenshots/img1.png"
OUT="$HERE/out"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"

WORK="$(mktemp -d)"
SERVER_PID=""
cleanup() { [[ -n "$SERVER_PID" ]] && kill "$SERVER_PID" 2>/dev/null; rm -rf "$WORK"; }
trap cleanup EXIT

# A copy of the extension with the stub loaded ahead of each page's own scripts.
cp -R "$EXT" "$WORK/ext"
cp "$HERE/stub.js" "$WORK/ext/stub.js"
for page in popup.html bulk.html; do
    perl -0pi -e 's#<head>#<head>\n<script src="stub.js"></script>#' "$WORK/ext/$page"
done
cp -R "$HERE/stages" "$WORK/stages"
cp "$APP_SHOT" "$WORK/app-screenshot.png"

PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$WORK" >/dev/null 2>&1 &
SERVER_PID=$!
for _ in $(seq 50); do curl -s -o /dev/null "http://127.0.0.1:$PORT/" && break; sleep 0.1; done

mkdir -p "$OUT"
shot() { # stage page, width, height, output name
    local png="$WORK/$4.png"
    "$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
        --user-data-dir="$WORK/profile" --virtual-time-budget=4000 --force-color-profile=srgb \
        --no-first-run --disable-component-update \
        --window-size="$2,$3" --screenshot="$png" "http://127.0.0.1:$PORT/stages/$1" >/dev/null 2>&1 &
    # Chrome can linger well after writing the screenshot (its updater), so wait for the file instead.
    local pid=$! i
    for i in $(seq 300); do
        [[ -s "$png" ]] && ! kill -0 "$pid" 2>/dev/null && break
        [[ -s "$png" && $i -gt 20 ]] && break
        sleep 0.1
    done
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    [[ -s "$png" ]] || { echo "error: no screenshot for $1" >&2; exit 1; }
    sips -s format jpeg -s formatOptions 92 "$png" --out "$OUT/$4.jpg" >/dev/null
    echo "  $OUT/$4.jpg ($2x$3)"
}

echo "Rendering store images:"
shot popup.html 1280 800 screenshot-1-popup
shot bulk.html 1280 800 screenshot-2-bulk
shot app.html 1280 800 screenshot-3-app
shot promo-small.html 440 280 promo-small
shot promo-marquee.html 1400 560 promo-marquee
[[ -n "${CHECK:-}" ]] && shot zz-check.html 1280 800 zz-check

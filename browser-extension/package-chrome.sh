#!/usr/bin/env bash
# Builds the Chrome Web Store upload: browser-extension/build/xdm-chrome-<version>.zip
#
# The zip holds the contents of chrome-extension/ at its root (the store rejects a zip whose
# manifest.json sits in a sub-folder), minus dotfiles such as .DS_Store. Before zipping it checks
# the things a store review (or the upload form) would otherwise bounce:
#   - manifest.json parses and every file it names exists
#   - every <script src> / stylesheet in the HTML pages is a local file that exists
#   - no remotely hosted code and no eval / new Function (MV3 forbids both)
#
# Usage: browser-extension/package-chrome.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/chrome-extension"
OUT_DIR="$HERE/build"

fail() { echo "error: $*" >&2; exit 1; }

command -v node >/dev/null || fail "node is needed to read manifest.json"
command -v zip >/dev/null || fail "zip not found"

# --- manifest ----------------------------------------------------------------
VERSION="$(node -e '
const m = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
if (m.manifest_version !== 3) { console.error("manifest_version must be 3"); process.exit(1); }
if (!/^\d+(\.\d+){0,3}$/.test(m.version)) { console.error("bad version: " + m.version); process.exit(1); }
if ((m.description || "").length > 132) { console.error("description is over 132 characters"); process.exit(1); }
if ((m.name || "").length > 75) { console.error("name is over 75 characters"); process.exit(1); }
console.log(m.version);
' "$SRC/manifest.json")" || fail "manifest.json check failed"

# Files the manifest refers to: icons, action icons, the service worker.
while IFS= read -r f; do
    [[ -f "$SRC/$f" ]] || fail "manifest.json names $f, which does not exist"
done < <(node -e '
const m = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
const files = [m.background.service_worker,
    ...Object.values(m.icons || {}),
    ...Object.values((m.action && m.action.default_icon) || {})];
if (m.action && m.action.default_popup) files.push(m.action.default_popup);
console.log(files.join("\n"));
' "$SRC/manifest.json")

# --- pages: local scripts and styles only ------------------------------------
for page in "$SRC"/*.html; do
    while IFS= read -r ref; do
        case "$ref" in
            http:*|https:*|//*) fail "$(basename "$page") loads remote code: $ref" ;;
        esac
        [[ -f "$SRC/${ref#./}" ]] || fail "$(basename "$page") references missing file $ref"
    done < <(grep -oE '<(script|link)[^>]+(src|href)="[^"]+"' "$page" | sed -E 's/.*(src|href)="([^"]+)"/\2/')
    if grep -qE '<script>|<script [^>]*>[^<]' "$page"; then
        fail "$(basename "$page") has an inline script (blocked by the MV3 CSP)"
    fi
done

# --- remote / dynamic code ---------------------------------------------------
if grep -nE '\beval\s*\(|new Function\s*\(|importScripts\s*\(|import\s*\(\s*["'\'']https?:' "$SRC"/*.js; then
    fail "dynamic or remote code found (see above)"
fi

# --- zip ---------------------------------------------------------------------
mkdir -p "$OUT_DIR"
ZIP="$OUT_DIR/xdm-chrome-$VERSION.zip"
rm -f "$ZIP"
(cd "$SRC" && zip -q -r -X "$ZIP" . -x '.*' -x '*/.*')

echo "Built $ZIP ($(du -h "$ZIP" | cut -f1 | tr -d ' '), version $VERSION)"
unzip -Z1 "$ZIP" | sort | sed 's/^/  /'

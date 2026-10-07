#!/usr/bin/env bash
# Builds the addons.mozilla.org (AMO) upload: browser-extension/build/xdm-firefox-<version>.zip
#
# Runs Mozilla's own linter (web-ext lint, the same checks AMO runs on upload) and fails on errors,
# then zips firefox-extension/ with manifest.json at the root, minus dotfiles such as .DS_Store.
# The sources are plain, unminified JS, so AMO needs no separate source-code upload.
#
# Usage: browser-extension/package-firefox.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/firefox-extension"
OUT_DIR="$HERE/build"

fail() { echo "error: $*" >&2; exit 1; }

command -v node >/dev/null || fail "node is needed to read manifest.json"
command -v npx >/dev/null || fail "npx is needed to run web-ext"

VERSION="$(node -e '
const m = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
const g = (m.browser_specific_settings || {}).gecko || {};
if (!g.id) { console.error("browser_specific_settings.gecko.id is missing"); process.exit(1); }
if (!g.data_collection_permissions) { console.error("gecko.data_collection_permissions is missing (AMO requires it)"); process.exit(1); }
if ((m.name || "").length > 50) { console.error("name is over 50 characters"); process.exit(1); }
console.log(m.version);
' "$SRC/manifest.json")" || fail "manifest.json check failed"

npx -y web-ext@latest lint --source-dir "$SRC" --self-hosted=false --output text || fail "web-ext lint reported errors"

mkdir -p "$OUT_DIR"
ZIP="$OUT_DIR/xdm-firefox-$VERSION.zip"
rm -f "$ZIP"
(cd "$SRC" && zip -q -r -X "$ZIP" . -x '.*' -x '*/.*')

echo "Built $ZIP ($(du -h "$ZIP" | cut -f1 | tr -d ' '), version $VERSION)"
unzip -Z1 "$ZIP" | sort | sed 's/^/  /'

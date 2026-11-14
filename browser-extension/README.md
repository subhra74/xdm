# XDM Browser Extension

Source code and packaging scripts for Xtreme Download Manager (XDM) browser extensions.

- `chrome-extension/`: Manifest V3 extension for Chromium-based browsers (Chrome, Edge, Brave, etc.)
- `firefox-extension/`: Manifest V2 extension for Firefox / Mozilla Add-ons
- `_locales/`: Canonical WebExtension internationalization files (Chrome JSON format)
- `tools/`: Development and CI helper scripts

## Translations & Internationalization

Translations are managed via Weblate. The canonical message files reside in `_locales/<lang>/messages.json`, with `_locales/en/messages.json` being the baseline.

### Syncing Locales

To sync translation files from `_locales/` into both `chrome-extension/_locales` and `firefox-extension/_locales`:

```bash
node tools/sync-locales.js
```

This runs automatically during packaging (`package-chrome.sh` and `package-firefox.sh`).

### Validating Locales

To check that all translation files are valid JSON, match English keys, and preserve placeholders:

```bash
node tools/check-locales.js
```

## Packaging

- Chrome: `./package-chrome.sh` (builds `build/xdm-chrome-<version>.zip`)
- Firefox: `./package-firefox.sh` (builds `build/xdm-firefox-<version>.zip`)

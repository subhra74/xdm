"use strict";

/**
 * The extension's half of hls-hook.js: runs in the content-script world of every frame and passes
 * the playlist text the page script found on to the background (RequestWatcher.onPlaylistBody).
 * The page itself can raise the same event, so the background only uses a body for a URL it saw
 * this tab request, and only as text for XDM to parse.
 */
(() => {
    if (globalThis.__xdmHlsRelay) {
        return;
    }
    globalThis.__xdmHlsRelay = true;
    const MAX_CHARS = 256 * 1024;

    document.addEventListener("xdm-hls-body", e => {
        let msg;
        try {
            msg = JSON.parse(e.detail);
        } catch (err) {
            return;
        }
        if (!msg || typeof msg.url !== "string" || typeof msg.body !== "string" || msg.body.length > MAX_CHARS) {
            return;
        }
        try {
            chrome.runtime.sendMessage({ type: "hls-body", url: msg.url, body: msg.body }).catch(() => { });
        } catch (err) {
            // The extension was reloaded or updated: this frame's script is orphaned.
        }
    });
})();

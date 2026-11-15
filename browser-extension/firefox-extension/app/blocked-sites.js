"use strict";

/**
 * Sites the extension never takes anything from, whatever XDM's own settings say: no video capture,
 * no bulk picker, no download or context-menu handover. Whatever happens on these sites is left to
 * the browser. The same list and rules as the Chrome extension's blocked-sites.js (and XDM's
 * BlockedSites); keep them in step. Not configurable.
 *
 * A classic script, not a module: the background page loads it with a plain <script> tag, and the
 * bulk picker loads it the same way ahead of its modules (classify.js uses isBlockedUrl).
 */
const BLOCKED_DOMAINS = [
    "youtube.com",
    "youtu.be",
    "youtube-nocookie.com",
    "youtubekids.com",
    "ytimg.com"          // thumbnails and player assets
];

/** True for one of the domains above or any subdomain of it ("m.youtube.com", "i.ytimg.com"). */
function isBlockedHost(hostname) {
    if (!hostname) {
        return false;
    }
    const host = hostname.toLowerCase().replace(/\.$/, "");
    return BLOCKED_DOMAINS.some(d => host === d || host.endsWith("." + d));
}

/** isBlockedHost for a URL or an origin; false for anything that does not parse. */
function isBlockedUrl(url) {
    if (!url) {
        return false;
    }
    try {
        return isBlockedHost(new URL(url).hostname);
    } catch (e) {
        return false;
    }
}

"use strict";

/**
 * Sites the extension never takes anything from, whatever XDM's own settings say. The Chrome Web
 * Store does not allow extensions that download from YouTube, so the extension stays out of it
 * entirely: no video capture, no bulk picker, no download or context-menu handover. Whatever
 * happens on these sites is left to the browser. (YouTube playback is POST-based and was never
 * capturable anyway - media is only replayed from a GET - so this mostly keeps the bulk picker and
 * the context menu off it.)
 *
 * Unlike XDM's blockedHosts, this list is not configurable: it is part of what the store reviewed.
 */
const BLOCKED_DOMAINS = [
    "youtube.com",
    "youtu.be",
    "youtube-nocookie.com",
    "youtubekids.com",
    "ytimg.com"          // thumbnails and player assets
];

/** True for one of the domains above or any subdomain of it ("m.youtube.com", "i.ytimg.com"). */
export function isBlockedHost(hostname) {
    if (!hostname) {
        return false;
    }
    const host = hostname.toLowerCase().replace(/\.$/, "");
    return BLOCKED_DOMAINS.some(d => host === d || host.endsWith("." + d));
}

/** isBlockedHost for a URL or an origin; false for anything that does not parse. */
export function isBlockedUrl(url) {
    if (!url) {
        return false;
    }
    try {
        return isBlockedHost(new URL(url).hostname);
    } catch (e) {
        return false;
    }
}

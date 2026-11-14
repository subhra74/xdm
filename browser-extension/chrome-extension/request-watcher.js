"use strict";
import Logger from './logger.js';
import { isBlockedUrl } from './blocked-sites.js';

// How long a watched request stays usable for a download that follows it. onDeterminingFilename
// fires milliseconds after the response headers, so this only has to outlast a slow disk prompt.
const OBSERVED_TTL_MS = 60000;
const OBSERVED_MAX = 200;
const DOWNLOAD_RESOURCE_TYPES = new Set(["main_frame", "sub_frame", "other", "object"]);
// An HLS playlist response is held this long for its text from the page (hls-hook.js) before it is
// reported without it. The text follows the headers within milliseconds when the page script saw
// the request; when it did not (a worker loaded it), this is the delay XDM's own fetch starts after.
const PLAYLIST_HOLD_MS = 1500;
// Text that arrived before its response was reported is kept this long, and at most this many.
const PLAYLIST_BODY_TTL_MS = 10000;
const PLAYLIST_BODY_MAX = 32;
const PLAYLIST_BODY_MAX_CHARS = 256 * 1024;
const M3U8_PATH = /\.m3u8(?:$|[/;])/i;

export default class RequestWatcher {
    constructor(callback) {
        this.logger = new Logger();
        this.blockedHosts = [];
        this.mediaExts = [];
        this.requestMap = new Map();
        // Download candidates the browser was seen fetching, keyed by URL. See observe().
        this.observed = new Map();
        this.callback = callback;
        this.mediaTypes = [];
        this.onSendHeadersEventCallback = this.onSendHeadersEvent.bind(this);
        this.onHeadersReceivedEventCallback = this.onHeadersReceivedEvent.bind(this);
        this.onErrorOccurredEventCallback = this.onErrorOccurredEvent.bind(this);
        this.urlPatterns = [];
        this.requestFileExts = [];
        // Set by the app while the service worker is starting up. Until it settles the lists above
        // are empty, so a response is held rather than judged against them: the media request
        // that woke the worker would otherwise never match.
        this.gate = null;
        this.registered = false;
        // HLS responses waiting for their playlist text, and text waiting for its response; both
        // keyed by playlistKey(). See reportMedia().
        this.heldPlaylists = new Map();
        this.playlistBodies = new Map();
    }

    updateConfig(config) {
        if (config.blockedHosts) {
            this.blockedHosts = config.blockedHosts
        }
        if (config.mediaExts) {
            this.mediaExts = config.mediaExts
        }
        if (config.mediaTypes) {
            this.mediaTypes = config.mediaTypes
        }
        if (config.requestFileExts) {
            this.requestFileExts = config.requestFileExts
        }
        if (config.urlPatterns) {
            this.urlPatterns = config.urlPatterns.map(pattern => {
                try {
                    return new RegExp(pattern, "i");
                } catch { }
            }).filter(item => item || false);
        }
    }

    isMatchingRequest(res) {
        // A failed response (a 410 from an expired or used-up CDN token, a 403, ...) is not media:
        // XDM would only replay the request and get the same error back.
        if (res.statusCode < 200 || res.statusCode >= 300) {
            return false;
        }
        let u = new URL(res.url);

        let hostName = u.host;
        if (this.blockedHosts.find(h => hostName.indexOf(h) >= 0)) {
            return false;
        }

        let path = u.pathname;
        let upath = path.toUpperCase();
        // mediaExts arrive without a leading dot, in whatever case they were typed in XDM's settings.
        if (this.mediaExts.find(e => upath.endsWith("." + e.toUpperCase()))) {
            return true;
        }

        if (this.requestFileExts.find(e => upath.endsWith(e))) {
            return true;
        }

        try {
            if (this.urlPatterns.find(re => re.test(res.url))) {
                return true;
            }
        } catch { }

        let mediaType = res.responseHeaders.find(h => h["name"].toUpperCase() === "CONTENT-TYPE");
        // MIME types are case-insensitive (RFC 2045), so "Video/MP4" must match "video/" too.
        let mimeValue = mediaType ? (mediaType["value"] || "").toLowerCase() : "";
        if (mimeValue && this.mediaTypes.find(m => mimeValue.indexOf(m.toLowerCase()) >= 0)) {
            return true;
        }
    }

    /**
     * Whether a response could be the one behind a browser download. Deliberately loose - the
     * takeover decision is made later, on the name Chrome settles on - but it keeps ordinary page
     * loads and subresources out of the cache. Only navigations and `other` (which covers
     * `<a download>` and downloads started by script) can turn into a download.
     */
    isDownloadCandidate(res) {
        if (!DOWNLOAD_RESOURCE_TYPES.has(res.type)) {
            return false;
        }
        if (res.statusCode < 200 || res.statusCode >= 300) {
            return false;
        }
        let hostName = new URL(res.url).host;
        if (this.blockedHosts.find(h => hostName.indexOf(h) >= 0)) {
            return false;
        }
        let headers = res.responseHeaders || [];
        let disposition = headers.find(h => h.name.toUpperCase() === "CONTENT-DISPOSITION");
        if (disposition && /attachment/i.test(disposition.value || "")) {
            return true;
        }
        let contentType = headers.find(h => h.name.toUpperCase() === "CONTENT-TYPE");
        return !(contentType && /^\s*(text\/html|application\/xhtml\+xml)/i.test(contentType.value || ""));
    }

    onSendHeadersEvent(info) {
        // Every method is recorded: the cache keeps what was actually sent, and each reader applies
        // its own method rule. A redirect hop re-fires this under the same requestId, so carry the
        // URL the chain started at forward - DownloadItem.url is that one, finalUrl the last hop.
        let prev = this.requestMap.get(info.requestId);
        info.originUrl = prev ? prev.originUrl : info.url;
        // Re-inserted rather than overwritten, so the map stays in timestamp order for sweep().
        this.requestMap.delete(info.requestId);
        this.requestMap.set(info.requestId, info);
        this.sweep();
    }

    onHeadersReceivedEvent(res) {
        let reqId = res.requestId;
        let req = this.requestMap.get(reqId);
        if (!req) {
            return;
        }
        if (res.statusCode >= 300 && res.statusCode < 400) {
            // A redirect: keep the entry so the next hop inherits originUrl. That hop overwrites
            // it, so the cache ends up holding the final request, which is the one to replay.
            return;
        }
        this.requestMap.delete(reqId);
        if (this.gate) {
            // Held in arrival order, ahead of any onDeterminingFilename waiting on the same gate.
            this.gate.then(() => this.onResponse(req, res));
            return;
        }
        this.onResponse(req, res);
    }

    onResponse(req, res) {
        // Held through startup, then XDM turned out not to be running: nothing to do with it.
        if (!this.registered) {
            return;
        }
        let candidate = this.isDownloadCandidate(res);
        // Media is only ever captured from a GET: no request body is recorded, so nothing else
        // could be replayed.
        let media = this.callback && req.method === "GET" && this.isMatchingRequest(res);
        if (!candidate && !media) {
            // The common case by far (every image, script, XHR, ...): nothing to keep or report.
            return;
        }
        // YouTube is off limits (blocked-sites.js): anything a YouTube page or embedded player
        // asked for. Neither cached nor reported, so the browser keeps it.
        if (isBlockedUrl(res.url) || isBlockedUrl(req.initiator)) {
            return;
        }
        let data = this.createRequestData(req, res, null, null, req.tabId);
        if (candidate) {
            this.observe(data, req.originUrl);
        }
        if (req.tabId === -1) {
            media && this.reportMedia(data, req.tabId);
            return;
        }
        // Fills the tab fields in on the cached object too, so a download reads them as well.
        chrome.tabs.get(req.tabId, tab => {
            if (chrome.runtime.lastError || !tab) {
                media && this.reportMedia(data, req.tabId);
                return;
            }
            if (isBlockedUrl(tab.url)) {
                // A request with no initiator (typed or bookmarked URL) made from a YouTube tab.
                this.forget(data);
                return;
            }
            data.tabTitle = tab.title;
            data.tabUrl = tab.url;
            media && this.reportMedia(data, req.tabId);
        });
    }

    /**
     * Sends a media response to XDM. An HLS playlist goes with the text the page's player received
     * (manifestBody) when hls-hook.js read it: some CDNs sign a playlist link for one request, so
     * XDM's own fetch of it fails. The text usually comes in just after the headers, so the
     * response is held for it up to PLAYLIST_HOLD_MS, then sent without.
     */
    reportMedia(data, tabId) {
        if (tabId === -1 || !this.isPlaylistResponse(data)) {
            this.callback(data);
            return;
        }
        const key = this.playlistKey(tabId, data.url);
        if (!key) {
            this.callback(data);
            return;
        }
        const body = this.playlistBodies.get(key);
        if (body && Date.now() - body.ts <= PLAYLIST_BODY_TTL_MS) {
            this.playlistBodies.delete(key);
            data.manifestBody = body.text;
            this.callback(data);
            return;
        }
        // A repeat of a request still waiting (a retry, a live refresh): the older one goes as it is.
        this.releasePlaylist(key);
        const timer = setTimeout(() => this.releasePlaylist(key), PLAYLIST_HOLD_MS);
        this.heldPlaylists.set(key, { data: data, timer: timer });
    }

    /** Sends a held playlist response now, with whatever text it has. */
    releasePlaylist(key) {
        const held = this.heldPlaylists.get(key);
        if (!held) {
            return;
        }
        clearTimeout(held.timer);
        this.heldPlaylists.delete(key);
        this.registered && this.callback(held.data);
    }

    /**
     * Playlist text hls-relay.js passed on from a frame of tab [tabId]. The page could send any text
     * under any URL, so it is only ever used for a response this tab really got, and XDM only parses
     * it (as it would parse what it fetched itself).
     */
    onPlaylistBody(tabId, url, text) {
        if (!this.registered || tabId === undefined || tabId === -1 || typeof text !== "string"
            || text.length > PLAYLIST_BODY_MAX_CHARS) {
            return;
        }
        const key = this.playlistKey(tabId, url);
        if (!key) {
            return;
        }
        const held = this.heldPlaylists.get(key);
        if (held) {
            held.data.manifestBody = text;
            this.releasePlaylist(key);
            return;
        }
        // The page read it before the response was reported (the tab lookup is async): keep it briefly.
        const now = Date.now();
        this.playlistBodies.delete(key);
        this.playlistBodies.set(key, { text: text, ts: now });
        for (const [k, v] of this.playlistBodies) {
            if (now - v.ts <= PLAYLIST_BODY_TTL_MS && this.playlistBodies.size <= PLAYLIST_BODY_MAX) {
                break;
            }
            this.playlistBodies.delete(k);
        }
    }

    /** ".m3u8" in the path or an mpegurl Content-Type: what hls-hook.js reads too. */
    isPlaylistResponse(data) {
        for (const name in data.responseHeaders) {
            if (name.toLowerCase() === "content-type"
                && data.responseHeaders[name].some(v => /mpegurl/i.test(v || ""))) {
                return true;
            }
        }
        try {
            return M3U8_PATH.test(new URL(data.url).pathname);
        } catch (e) {
            return false;
        }
    }

    /** Tab plus URL without its fragment: the page sees the URL without one, webRequest may keep it. */
    playlistKey(tabId, url) {
        try {
            const u = new URL(url);
            u.hash = "";
            return tabId + " " + u.href;
        } catch (e) {
            return null;
        }
    }

    /** Caches a download candidate under its final URL, and under the chain's first if it differs. */
    observe(data, originUrl) {
        data.ts = Date.now();
        this.observed.delete(data.url);
        this.observed.set(data.url, data);
        if (originUrl && originUrl !== data.url) {
            this.observed.delete(originUrl);
            this.observed.set(originUrl, data);
        }
    }

    /** Drops a cached candidate again, under every URL it was stored under. */
    forget(data) {
        for (let [url, cached] of this.observed) {
            if (cached === data) {
                this.observed.delete(url);
            }
        }
    }

    /**
     * The request the browser made for a download, or undefined if it was not watched (or has
     * aged out). finalUrl is tried first: it is the hop that actually delivered the file.
     */
    findObserved(download) {
        for (let url of [download.finalUrl, download.url]) {
            let data = url && this.observed.get(url);
            if (data && Date.now() - data.ts <= OBSERVED_TTL_MS) {
                return data;
            }
        }
        return undefined;
    }

    /** Evicts aged-out entries, then the oldest ones beyond the cap. Maps iterate in insertion order. */
    sweep() {
        let now = Date.now();
        for (let [url, data] of this.observed) {
            if (now - data.ts <= OBSERVED_TTL_MS && this.observed.size <= OBSERVED_MAX) {
                break;
            }
            this.observed.delete(url);
        }
        // Requests that never reached a final response and never errored (e.g. a redirect to a
        // scheme webRequest does not see) would otherwise sit here for the worker's lifetime.
        for (let [id, req] of this.requestMap) {
            if (now - req.timeStamp <= OBSERVED_TTL_MS) {
                break;
            }
            this.requestMap.delete(id);
        }
    }

    onErrorOccurredEvent(info) {
        let reqId = info.requestId;
        this.requestMap.delete(reqId);
    }

    // Both are safe to call repeatedly: the app registers on every reply from XDM and unregisters
    // on every disconnect.
    register() {
        if (this.registered) {
            return;
        }
        this.registered = true;
        chrome.webRequest.onSendHeaders.addListener(
            this.onSendHeadersEventCallback,
            { urls: ["http://*/*", "https://*/*"] },
            ["extraHeaders", "requestHeaders"]
        );

        chrome.webRequest.onHeadersReceived.addListener(
            this.onHeadersReceivedEventCallback,
            { urls: ["http://*/*", "https://*/*"] },
            ["extraHeaders", "responseHeaders"]
        );

        chrome.webRequest.onErrorOccurred.addListener(
            this.onErrorOccurredEventCallback,
            { urls: ["http://*/*", "https://*/*"] }
        );
    }

    unRegister() {
        if (!this.registered) {
            return;
        }
        this.registered = false;
        // Nothing half-seen is worth keeping: by the time the listeners return, these are stale.
        this.requestMap.clear();
        this.observed.clear();
        for (const held of this.heldPlaylists.values()) {
            clearTimeout(held.timer);
        }
        this.heldPlaylists.clear();
        this.playlistBodies.clear();
        chrome.webRequest.onSendHeaders.removeListener(this.onSendHeadersEventCallback);
        chrome.webRequest.onHeadersReceived.removeListener(this.onHeadersReceivedEventCallback);
        chrome.webRequest.onErrorOccurred.removeListener(this.onErrorOccurredEventCallback);
    }

    createRequestData(req, res, title, tabUrl, tabId) {
        let data = {
            url: res.url,
            // "media" when a <video>/<audio> element loaded it itself (a whole file), "xmlhttprequest"
            // when page script fetched it, e.g. segments fed to MediaSource.
            resourceType: res.type,
            tabTitle: title,
            requestHeaders: {},
            responseHeaders: {},
            cookie: undefined,
            method: req.method,
            userAgent: navigator.userAgent,
            tabUrl: tabUrl,
            tabId: tabId + ""
        };

        let cookies = [];

        if (req.extraHeaders) {
            req.extraHeaders.forEach(h => {
                if (h.name === 'Cookie' || h.name === 'cookie') {
                    cookies.push(h.value);
                }
                this.addToDict(data.requestHeaders, h.name, h.value);
            });
        }
        if (req.requestHeaders) {
            req.requestHeaders.forEach(h => {
                if (h.name === 'Cookie' || h.name === 'cookie') {
                    cookies.push(h.value);
                }
                this.addToDict(data.requestHeaders, h.name, h.value);
            });
        }
        if (res.responseHeaders) {
            res.responseHeaders.forEach(h => {
                this.addToDict(data.responseHeaders, h.name, h.value);
            });
        }
        if (cookies.length > 0) {
            data.cookie = cookies.join("; ");
        }
        return data;
    }

    addToDict(dict, key, value) {
        let values = dict[key];
        if (values) {
            values.push(value);
        } else {
            dict[key] = [value];
        }
    }
}
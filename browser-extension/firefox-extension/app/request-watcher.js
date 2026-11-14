"use strict";

// How long a request may sit between its headers going out and its response arriving before it is
// dropped as abandoned.
const PENDING_TTL_MS = 60000;
// Only a navigation, in a tab or in a frame, is something Firefox itself turns into a download.
// Everything else is left alone, deliberately:
// - fetch() and XMLHttpRequest are both `xmlhttprequest` and must never be taken over.
// - `other` is Firefox's catch-all: besides "Save Link As" and downloads.download() it also holds
//   <link rel=prefetch> (TYPE_OTHER), FedCM, WebTransport and more, which are not downloads.
// - `object` (<object>/<embed>) shows fallback content for a type it cannot render; no download.
const DOWNLOAD_RESOURCE_TYPES = new Set(["main_frame", "sub_frame"]);
// Without a Content-Disposition: attachment, Firefox shows these itself rather than downloading
// them, so they are left alone - the same split Chrome's onDeterminingFilename makes for us there.
// application/ogg is played inline like audio/ and video/ are.
// Longest HLS playlist whose text is read on its way to the page (see readPlaylistBody).
const PLAYLIST_BODY_MAX_CHARS = 256 * 1024;
const PLAYLIST_SIGNATURE = /^\uFEFF?\s*#EXTM3U/;
const M3U8_PATH = /\.m3u8(?:$|[/;])/i;
const INLINE_TYPE = /^\s*(text\/|image\/|video\/|audio\/|application\/(pdf|json|xml|xhtml\+xml|javascript|ogg)\b|[^;]*\+xml\b)/i;

/**
 * Firefox has no downloads.onDeterminingFilename, but it does keep blocking webRequest, so the
 * takeover happens on the response itself: a GET that would become a download is cancelled and
 * handed to XDM with the headers the browser actually sent. The rules for what counts as a
 * download candidate and as media match the Chrome extension.
 */
class RequestWatcher {
    constructor(callback, downloadCallback) {
        this.logger = new Logger();
        this.blockedHosts = [];
        this.mediaExts = [];
        this.requestMap = new Map();
        this.callback = callback;
        // Called synchronously with a download candidate; returns true when XDM takes it over.
        this.downloadCallback = downloadCallback;
        this.mediaTypes = [];
        this.onSendHeadersEventCallback = this.onSendHeadersEvent.bind(this);
        this.onHeadersReceivedEventCallback = this.onHeadersReceivedEvent.bind(this);
        this.onErrorOccurredEventCallback = this.onErrorOccurredEvent.bind(this);
        this.urlPatterns = [];
        this.requestFileExts = [];
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
     * Whether Firefox is about to download this response itself: a navigation (see
     * DOWNLOAD_RESOURCE_TYPES) whose response it will not display. Errs towards leaving a response
     * to the browser - a missed takeover only means the browser downloads it as usual.
     */
    isDownloadCandidate(res) {
        if (!DOWNLOAD_RESOURCE_TYPES.has(res.type)) {
            return false;
        }
        // 204/205 carry no body: Firefox leaves the page as it is and downloads nothing.
        if (res.statusCode < 200 || res.statusCode >= 300 || res.statusCode === 204 || res.statusCode === 205) {
            return false;
        }
        let hostName = new URL(res.url).host;
        if (this.blockedHosts.find(h => hostName.indexOf(h) >= 0)) {
            return false;
        }
        let headers = res.responseHeaders || [];
        let disposition = headers.find(h => h.name.toUpperCase() === "CONTENT-DISPOSITION");
        // The disposition type comes first; a bare /attachment/ would also match
        // `inline; filename="attachment.zip"`, which Firefox displays.
        if (disposition && /^\s*attachment\b/i.test(disposition.value || "")) {
            return true;
        }
        let contentType = headers.find(h => h.name.toUpperCase() === "CONTENT-TYPE");
        // No type at all: Firefox sniffs the body and may well display it, so it is not a download.
        if (!contentType || !(contentType.value || "").trim()) {
            return false;
        }
        return !INLINE_TYPE.test(contentType.value);
    }

    onSendHeadersEvent(info) {
        // Every method is recorded: each reader applies its own method rule. A redirect hop re-fires
        // this under the same requestId and overwrites the entry, so the final hop is what is kept,
        // carrying forward the URL the chain started at (a download sends it as its source link).
        // Re-inserted rather than overwritten, so the map stays in timestamp order for sweep().
        let prev = this.requestMap.get(info.requestId);
        info.originUrl = prev ? prev.originUrl : info.url;
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
            // A redirect: the next hop replaces the entry.
            return;
        }
        this.requestMap.delete(reqId);
        if (res.url.startsWith(APP_BASE_URL + "/")) {
            // XDM itself. Never cancel the extension's own channel to it.
            return;
        }
        // Only a GET is ever taken over or captured: no request body is recorded, so nothing else
        // could be replayed.
        let get = req.method === "GET";
        let candidate = get && this.isDownloadCandidate(res);
        let media = this.callback && get && this.isMatchingRequest(res);
        if (!candidate && !media) {
            // The common case by far (every image, script, XHR, ...): nothing to report.
            return;
        }
        // YouTube is off limits (blocked-sites.js): anything a YouTube page or embedded player asked
        // for. Neither taken over nor reported, so the browser keeps it. originUrl here is Firefox's
        // own (what triggered the request); req.originUrl is where a redirect chain started.
        if (isBlockedUrl(res.url) || isBlockedUrl(res.originUrl) || isBlockedUrl(res.documentUrl)
            || (res.frameAncestors || []).some(f => isBlockedUrl(f.url))) {
            return;
        }
        let data = this.createRequestData(req, res, null, null, req.tabId);
        if (req.originUrl && req.originUrl !== data.url) {
            data.sourceUrl = req.originUrl;
        }
        let download = candidate && this.downloadCallback && this.downloadCallback(data, res) === true;
        if (download) {
            data.download = true;
        } else if (!media) {
            return;
        }
        let send = () => {
            if (req.tabId === -1) {
                this.callback(data);
                return;
            }
            chrome.tabs.get(req.tabId, tab => {
                if (!chrome.runtime.lastError && tab) {
                    // A request with nothing to tell where it came from, made in a YouTube tab.
                    if (isBlockedUrl(tab.url) && !data.download) {
                        return;
                    }
                    data.tabTitle = tab.title;
                    data.tabUrl = tab.url;
                }
                this.callback(data);
            });
        };
        // An HLS playlist goes with the text the page received, once it has all arrived: some CDNs
        // sign a playlist link for one request, so XDM's own fetch of it would fail.
        let reading = !download && this.readPlaylistBody(res, text => {
            if (text) {
                data.manifestBody = text;
            }
            send();
        });
        if (!reading) {
            send();
        }
        if (download) {
            return { cancel: true };
        }
    }

    /**
     * Copies an HLS playlist's text while Firefox delivers it to the page, and calls [done] with it
     * (null when it is not a playlist or runs past PLAYLIST_BODY_MAX_CHARS) once the response ends.
     * Every chunk is written on to the page as it arrives, unchanged. False, and [done] is never
     * called, when the response is not read: not a playlist by URL or type, or too large by its
     * Content-Length.
     */
    readPlaylistBody(res, done) {
        let headers = res.responseHeaders || [];
        let header = name => {
            let h = headers.find(h => h.name.toLowerCase() === name);
            return h ? (h.value || "") : "";
        };
        let path = "";
        try {
            path = new URL(res.url).pathname;
        } catch (e) {
            return false;
        }
        if (!/mpegurl/i.test(header("content-type")) && !M3U8_PATH.test(path)) {
            return false;
        }
        if (Number(header("content-length")) > PLAYLIST_BODY_MAX_CHARS) {
            return false;
        }
        let filter;
        try {
            filter = browser.webRequest.filterResponseData(res.requestId);
        } catch (e) {
            return false;
        }
        let decoder = new TextDecoder();
        let text = "";
        let keep = true;
        let finished = false;
        let finish = body => {
            if (!finished) {
                finished = true;
                done(body);
            }
        };
        filter.ondata = event => {
            filter.write(event.data);
            if (!keep) {
                return;
            }
            text += decoder.decode(event.data, { stream: true });
            if (text.length > PLAYLIST_BODY_MAX_CHARS
                || (text.length >= 64 && !PLAYLIST_SIGNATURE.test(text.slice(0, 64)))) {
                keep = false;
                text = "";
            }
        };
        filter.onstop = () => {
            try {
                filter.close();
            } catch (e) {
            }
            if (keep) {
                text += decoder.decode();
            }
            finish(keep && PLAYLIST_SIGNATURE.test(text.slice(0, 64)) ? text : null);
        };
        filter.onerror = () => finish(null);
        return true;
    }

    /** Drops requests that never reached a final response and never errored. */
    sweep() {
        let now = Date.now();
        for (let [id, req] of this.requestMap) {
            if (now - req.timeStamp <= PENDING_TTL_MS) {
                break;
            }
            this.requestMap.delete(id);
        }
    }

    onErrorOccurredEvent(info) {
        let reqId = info.requestId;
        this.requestMap.delete(reqId);
    }

    register() {
        chrome.webRequest.onSendHeaders.addListener(
            this.onSendHeadersEventCallback,
            { urls: ["http://*/*", "https://*/*"] },
            ["requestHeaders"]
        );

        chrome.webRequest.onHeadersReceived.addListener(
            this.onHeadersReceivedEventCallback,
            { urls: ["http://*/*", "https://*/*"] },
            ["blocking", "responseHeaders"]
        );

        chrome.webRequest.onErrorOccurred.addListener(
            this.onErrorOccurredEventCallback,
            { urls: ["http://*/*", "https://*/*"] }
        );
    }

    unRegister() {
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

        if (req.requestHeaders) {
            req.requestHeaders.forEach(h => {
                if (h.name.toLowerCase() === 'cookie') {
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

"use strict";
import Logger from './logger.js';

// How long a watched request stays usable for a download that follows it. onDeterminingFilename
// fires milliseconds after the response headers, so this only has to outlast a slow disk prompt.
const OBSERVED_TTL_MS = 60000;
const OBSERVED_MAX = 200;
const DOWNLOAD_RESOURCE_TYPES = new Set(["main_frame", "sub_frame", "other", "object"]);

export default class RequestWatcher {
    constructor(callback) {
        this.logger = new Logger();
        this.blockedHosts = [];
        this.mediaExts = [];
        this.fileExts = [];
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
    }

    updateConfig(config) {
        if (config.blockedHosts) {
            this.blockedHosts = config.blockedHosts
        }
        if (config.fileExts) {
            this.fileExts = config.fileExts
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
        if (this.mediaExts.find(e => upath.endsWith(e))) {
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
        if (mediaType && this.mediaTypes.find(m => mediaType["value"].indexOf(m) >= 0)) {
            return true;
        }

        if (this.fileExts.find(e => upath.endsWith("." + e))) {
            return true;
        }

        let contentDisposition = res.responseHeaders.find(h => h["name"].toUpperCase() === "CONTENT-DISPOSITION");
        if (contentDisposition && this.fileExts.find(ext => contentDisposition["value"].toUpperCase().indexOf("." + ext) >= 0)) {
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
        let candidate = this.isDownloadCandidate(res);
        // Media is only ever captured from a GET: no request body is recorded, so nothing else
        // could be replayed.
        let media = this.callback && req.method === "GET" && this.isMatchingRequest(res);
        if (!candidate && !media) {
            // The common case by far (every image, script, XHR, ...): nothing to keep or report.
            return;
        }
        let data = this.createRequestData(req, res, null, null, req.tabId);
        if (candidate) {
            this.observe(data, req.originUrl);
        }
        if (req.tabId === -1) {
            media && this.callback(data);
            return;
        }
        // Fills the tab fields in on the cached object too, so a download reads them as well.
        chrome.tabs.get(req.tabId, tab => {
            if (chrome.runtime.lastError || !tab) {
                media && this.callback(data);
                return;
            }
            data.tabTitle = tab.title;
            data.tabUrl = tab.url;
            media && this.callback(data);
        });
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

    register() {
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
        chrome.webRequest.onSendHeaders.removeListener(this.onSendHeadersEventCallback);
        chrome.webRequest.onHeadersReceived.removeListener(this.onHeadersReceivedEventCallback);
        chrome.webRequest.onErrorOccurred.removeListener(this.onErrorOccurredEventCallback);
    }

    createRequestData(req, res, title, tabUrl, tabId) {
        let data = {
            url: res.url,
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
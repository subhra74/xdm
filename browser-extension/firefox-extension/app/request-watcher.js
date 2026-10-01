"use strict";

// How long a request may sit between its headers going out and its response arriving before it is
// dropped as abandoned.
const PENDING_TTL_MS = 60000;
const DOWNLOAD_RESOURCE_TYPES = new Set(["main_frame", "sub_frame", "other", "object"]);
// Without a Content-Disposition: attachment, Firefox shows these itself rather than downloading
// them, so they are left alone - the same split Chrome's onDeterminingFilename makes for us there.
const INLINE_TYPE = /^\s*(text\/|image\/|video\/|audio\/|application\/(pdf|json|xml|xhtml\+xml|javascript)\b|[^;]*\+xml\b)/i;

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
        this.fileExts = [];
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
     * Whether a response could be the one behind a browser download. Only navigations and `other`
     * (which covers `<a download>` and downloads started by script) can turn into a download, and
     * a page is never one.
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
        return !(contentType && INLINE_TYPE.test(contentType.value || ""));
    }

    onSendHeadersEvent(info) {
        // Every method is recorded: each reader applies its own method rule. A redirect hop re-fires
        // this under the same requestId and overwrites the entry, so the final hop is what is kept.
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
        let data = this.createRequestData(req, res, null, null, req.tabId);
        let download = candidate && this.downloadCallback && this.downloadCallback(data, res) === true;
        if (download) {
            data.download = true;
        } else if (!media) {
            return;
        }
        if (req.tabId === -1) {
            this.callback(data);
        } else {
            chrome.tabs.get(req.tabId, tab => {
                if (!chrome.runtime.lastError && tab) {
                    data.tabTitle = tab.title;
                    data.tabUrl = tab.url;
                }
                this.callback(data);
            });
        }
        if (download) {
            return { cancel: true };
        }
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

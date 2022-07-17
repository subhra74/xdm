"use strict";

xdm.requestWatcher = {
    requests: {},
    responses: {},
    urlMap: {},
    callback: undefined,

    onErrorOccurred: function (error) {
        this.clearRequestResponse(error.requestId);
    },

    onCompleted: function (details) {
        this.clearRequestResponse(details.requestId);
    },

    clearRequestResponse: function (id) {
        var response = xdm.requestWatcher.responses[id];
        if (response && response.url) {
            delete xdm.requestWatcher.urlMap[response.url];
        }
        delete xdm.requestWatcher.requests[id];
        delete xdm.requestWatcher.responses[id];
    },

    onSendHeaders: function (info) {
        this.requests[info.requestId] = info;
    },

    onHeadersReceived: function (response) {
        this.responses[response.requestId] = response;
        var request = this.requests[response.requestId];
        if (this.callback) {
            this.callback.onResponse(request, response);
        }
        this.urlMap[response.url] = response.requestId;
    },

    onCreated: function (item) {
        try {
            if (!item) {
                return;
            }
            if (!xdm.monitoring.isMonitoring()) {
                return;
            }
            if (item.method && item.method === "POST") {
                return;
            }

            var requestId = this.urlMap[item.finalUrl];
            xdm.log("urlmap: " + requestId);
            if (!requestId) {
                return;
            }
            var response = this.responses[requestId];
            if (!response) {
                return;
            }
            if (!this.callback.isMatchingDownload(item, response)) {
                return;
            }
            chrome.downloads.cancel(item.id);
            chrome.downloads.erase({ id: item.id });
            chrome.downloads.removeFile(item.id);

            xdm.log(item.finalUrl + " " + item.referrer + " " +
                item.filename + " " + item.headers + " " + item.method);

            this.callback.onDownload(item, this.requests[requestId], this.responses[requestId]);
        } catch (ex) {
            xdm.log(ex);
        }
    },

    attach: function (callback) {
        this.callback = callback;
        //This will monitor and intercept files download if 
        //criteria matches and XDM is running
        //Use request array to get request headers
        chrome.webRequest.onHeadersReceived.addListener(
            this.onHeadersReceived.bind(this),
            { urls: ["http://*/*", "https://*/*"] },
            ["responseHeaders"]
        );

        chrome.webRequest.onSendHeaders.addListener(
            this.onSendHeaders.bind(this),
            { urls: ["http://*/*", "https://*/*"] },
            ["requestHeaders", "extraHeaders"]
        );

        chrome.webRequest.onErrorOccurred.addListener(
            this.onErrorOccurred.bind(this),
            { urls: ["http://*/*", "https://*/*"] }
        );

        chrome.webRequest.onCompleted.addListener(
            this.onCompleted.bind(this),
            { urls: ["http://*/*", "https://*/*"] }
        );

        chrome.downloads.onCreated.addListener(this.onCreated.bind(this));
    }
};
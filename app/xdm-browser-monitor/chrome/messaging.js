"use strict";
xdm.messaging = {
    xhrHost: "http://127.0.0.1:9614",
    nativePort: undefined,
    onDisconnect: function () { },
    onSync: function (data) { },
    connectWithApp: function (onSync, onDisconnect) {
        xdm.messaging.onDisconnect = onDisconnect;
        xdm.messaging.onSync = onSync;
        xdm.messaging.connectNative().then(function (port) {
            xdm.log("Connected successfully with native host");
            xdm.messaging.nativePort = port;
        }).catch(function () {
            xdm.log("Error with native messaging, trying with XHR");
            xdm.messaging.connectXHR();
        });
        chrome.runtime.onMessage.addListener(xdm.messaging.onPageMessage);
    },
    sendToXDM: function (request, response, file, video, referer) {
        xdm.log("sending to xdm: " + response.url+" "+xdm.messaging.nativePort);
        try {
            if (xdm.messaging.nativePort) {
                xdm.messaging.sendWithNativeMessaging(request, response, file, video, referer);
            } else {
                xdm.messaging.sendWithXHR(request, response, file, video, referer);
            }
        } catch (ex) { xdm.log(ex); }
    },
    sendUrlsToXDM: function (urls) {
        if (urls && urls.length > 0) {
            xdm.messaging.sendRecUrl(urls, 0, []);
        }
    },
    connectXHR: function () {
        setInterval(function () { xdm.messaging.pingXHR(); }, 5000);
    },
    connectNative: function () {
        return new Promise(function (resolve, reject) {
            xdm.messaging.nativePort = undefined;
            try {
                xdm.log("Connecting to native messaging host: xdm_chrome.native_host");
                var port = chrome.runtime.connectNative("xdm_chrome.native_host");
                xdm.log(port);
                if (!port) {
                    xdm.log("Unable to connect to native messaging host");
                    reject("Unable to connect to native messaging host");
                }
                xdm.log("Connected to native messaging host");
                port.onDisconnect.addListener(function () {
                    xdm.log("Disconnected from native messaging host!");
                    xdm.messaging.onDisconnect();
                    reject("Disconnected from native messaging host!");
                });
                resolve(port);
                port.onMessage.addListener(function (data) {
                    if (data.appExited) {
                        xdm.messaging.postNativeMessage({});
                        xdm.messaging.onDisconnect();
                    } else {
                        xdm.messaging.onSync(data);
                    }
                });
            } catch (err) {
                log("Error while creating native messaging host");
                xdm.log(err);
                reject("Unable to connect to native messaging host");
            }
        });
    },
    pingXHR: function () {
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function () {
            if (xhr.readyState == XMLHttpRequest.DONE) {
                if (xhr.status == 200) {
                    var data = JSON.parse(xhr.responseText);
                    xdm.messaging.onSync(data);
                }
                else {
                    xdm.messaging.onDisconnect();
                }
            }
        };
        xhr.open('GET', xdm.messaging.xhrHost + "/sync", true);
        xhr.send(null);
    },
    sendRecUrl: function (urls, index, data) {
        if (index > 0 && index == urls.length - 1) {
            xdm.log(data);
            if (xdm.messaging.nativePort) {
                xdm.log("Sending links to native host");
                xdm.messaging.postNativeMessage({ messageType: "links", messages: data });
            } else {
                var text = "";
                data.forEach(item => {
                    text += "url=" + item.url + "\r\n";
                    text += "res=realUA:" + navigator.userAgent + "\r\n";
                    Object.keys(item.cookies).forEach(function (key) {
                        text += "cookie=" + key + ":" + item.cookies[key] + "\r\n";
                    });
                    text += "\r\n\r\n";
                });
                var xhr = new XMLHttpRequest();
                xhr.open('POST', xdm.messaging.xhrHost + "/links", true);
                xhr.send(text);
            }
            return;
        }
        var url = urls[index];
        chrome.cookies.getAll({ "url": url }, function (cookies) {
            var cookieDict = {};
            cookies.forEach(cookie => {
                cookieDict[cookie.name] = cookie.value;
            });
            var linkItem = {
                url: url,
                cookies: cookieDict,
                responseHeaders: { realUA: [navigator.userAgent] }
            };
            data.push(linkItem);
            xdm.messaging.sendRecUrl(urls, index + 1, data);
        });
    },
    sendUrlToXDM: function (url) {
        xdm.log("sending to xdm: " + url);
        if (xdm.messaging.nativePort) {
            chrome.cookies.getAll({ "url": url }, function (cookies) {
                var cookieDict = {};
                cookies.forEach(cookie => {
                    cookieDict[cookie.name] = cookie.value;
                });
                var data = {
                    url: url,
                    cookies: cookieDict,
                    responseHeaders: { realUA: [navigator.userAgent] }
                }
                xdm.log(data);
                xdm.messaging.postNativeMessage({ messageType: "download", message: data });
            });
        } else {
            var data = "url=" + url + "\r\n";
            data += "res=realUA:" + navigator.userAgent + "\r\n";
            chrome.cookies.getAll({ "url": url }, function (cookies) {
                for (var i = 0; i < cookies.length; i++) {
                    var cookie = cookies[i];
                    data += "cookie=" + cookie.name + ":" + cookie.value + "\r\n";
                }
                xdm.log(data);
                var xhr = new XMLHttpRequest();
                xhr.open('POST', xdm.messaging.xhrHost + "/download", true);
                xhr.send(data);
            });
        }
    },
    sendWithNativeMessaging: function (request, response, file, video, referer) {
        var data = {
            url: response.url,
            file: file,
            requestHeaders: {},
            responseHeaders: {},
            cookies: {},
            method: request.method
        };
        var hasReferer = false;
        if (request.extraHeaders) {
            request.extraHeaders.forEach(header => {
                xdm.util.addToValueList(data.requestHeaders, header.name, header.value);
            });
        }
        if (request.requestHeaders) {
            request.requestHeaders.forEach(header => {
                if (header.name.toLowerCase() === 'referer') {
                    hasReferer = true;
                }
                xdm.util.addToValueList(data.requestHeaders, header.name, header.value);
            });
        }
        if (response.responseHeaders) {
            response.responseHeaders.forEach(header => {
                xdm.util.addToValueList(data.responseHeaders, header.name, header.value);
            });
        }
        xdm.util.addToValueList(data.responseHeaders, "tabId", request.tabId);
        xdm.util.addToValueList(data.responseHeaders, "realUA", navigator.userAgent);

        if (hasReferer === false && referer) {
            data += "req=Referer:" + referer + "\r\n";
        }
        xdm.messaging.postNativeMessage({ messageType: video ? "video" : "download", message: data });
    },
    sendWithXHR: function (request, response, file, video, referer) {
        xdm.log("Sending to xdm using xhr");
        var data = "url=" + response.url + "\r\n";
        if (file) {
            data += "file=" + file + "\r\n";
        }
        var hasReferer = false;
        if (request.extraHeaders) {
            for (var i = 0; i < request.extraHeaders.length; i++) {
                data += "req=" + request.extraHeaders[i].name + ":" + request.extraHeaders[i].value + "\r\n";
                xdm.log("extraHeaders: " + request.extraHeaders[i].name + ":" + request.extraHeaders[i].value);
            }
        }
        if (request.requestHeaders) {
            for (var i = 0; i < request.requestHeaders.length; i++) {
                if (request.requestHeaders[i].name == 'Referer') {
                    hasReferer = true;
                }
                data += "req=" + request.requestHeaders[i].name + ":" + request.requestHeaders[i].value + "\r\n";
                xdm.log("requestHeaders: " + request.requestHeaders[i].name + ":" + request.requestHeaders[i].value);
            }
        }
        if (response.responseHeaders) {
            for (var i = 0; i < response.responseHeaders.length; i++) {
                data += "res=" + response.responseHeaders[i].name + ":" + response.responseHeaders[i].value + "\r\n";
                xdm.log("responseHeaders: " + response.responseHeaders[i].name + ":" + response.responseHeaders[i].value);
            }
        }
        if (hasReferer === false && referer) {
            data += "req=Referer:" + referer + "\r\n";
        }
        data += "res=tabId:" + request.tabId + "\r\n";
        data += "res=realUA:" + navigator.userAgent + "\r\n";
        chrome.cookies.getAll({ "url": response.url }, function (cookies) {
            if (cookies) {
                for (var i = 0; i < cookies.length; i++) {
                    var cookie = cookies[i];
                    data += "cookie=" + cookie.name + ":" + cookie.value + "\r\n";
                }
            }
            xdm.log(data);
            var xhr = new XMLHttpRequest();
            xhr.open('POST', xdm.messaging.xhrHost + (video ? "/video" : "/download"), true);
            xhr.send(data);
        });
    },
    postNativeMessage: function (message) {
        if (xdm.messaging.nativePort) {
            try {
                xdm.messaging.nativePort.postMessage(message);
            } catch (err) {
                xdm.log(err);
                try { xdm.messaging.nativePort.disconnect(); } catch { }
                xdm.messaging.nativePort = undefined;
                xdm.messaging.onDisconnect();
            }
        }
    },
    onPageMessage: function (request, sender, sendResponse) {
        if (request.type === "links") {
            xdm.messaging.sendUrlsToXDM(request.links);
            sendResponse({ done: "done" });
        }
        else if (request.type === "stat") {
            var resp = {
                isDisabled: xdm.monitoring.state.disabled,
                list: xdm.monitoring.videoList
            };
            sendResponse(resp);
        }
        else if (request.type === "cmd") {
            xdm.monitoring.state.disabled = request.disable;
            xdm.log("disabled " + disabled);
        }
        else if (request.type === "vid") {
            if (xdm.monitoring.state.isXDMUp && xdm.messaging.nativePort) {
                xdm.messaging.postNativeMessage({ messageType: "videoIds", videoIds: [request.itemId + ""] });
            } else {
                var xhr = new XMLHttpRequest();
                xhr.open('POST', xdm.messaging.xhrHost + "/item", true);
                xhr.send(request.itemId);
            }
        }
        else if (request.type === "clear") {
            if (xdm.messaging.nativePort) {
                xdm.messaging.postNativeMessage({ messageType: "clear" });
            }
            else {
                var xhr = new XMLHttpRequest();
                xhr.open('GET', xdm.messaging.xhrHost + "/clear", true);
                xhr.send();
            }
        }
    }
};
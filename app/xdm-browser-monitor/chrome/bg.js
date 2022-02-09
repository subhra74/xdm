"use strict";

(function () {
    var requests = {};
    var preRequests = {};
    var blockedHosts = ["update.microsoft.com", "windowsupdate.com", "thwawte.com"];
    var videoUrls = [".facebook.com|pagelet", "player.vimeo.com/", "instagram.com/p/"];
    var fileExts = ["3GP", "7Z", "AVI", "BZ2", "DEB", "DOC", "DOCX", "EXE", "GZ", "ISO",
        "MSI", "PDF", "PPT", "PPTX", "RAR", "RPM", "XLS", "XLSX", "SIT", "SITX", "TAR", "JAR", "ZIP", "XZ"];
    var vidExts = ["MP4", "M3U8", "F4M", "WEBM", "OGG", "MP3", "AAC", "FLV", "MKV", "DIVX",
        "MOV", "MPG", "MPEG", "OPUS"];
    var blockedMimeList = ["text/javascript", "application/javascript", "text/css", "text/html"];
    var isXDMUp = true;
    var monitoring = true;
    var debug = true;
    var xdmHost = "http://127.0.0.1:9614";
    var disabled = false;
    var lastIcon;
    var lastPopup;
    var videoList = [];
    var mimeList = [];
    var hasNativeMessagingHost = false;
    var port = undefined;
    var videoUrlsWithPostReq = ["ubei/v1/player?key=", "ubei/v1/next?key="];

    function log(msg) {
        if (debug) {
            try {
                console.log(msg);
            } catch { }
        }
    }

    function postNativeMessage(message) {
        if (hasNativeMessagingHost && port) {
            log(JSON.stringify(message));
            try {
                port.postMessage(message);
            } catch (err) {
                log(err);
                hasNativeMessagingHost = false;
                port = undefined;
            }
        }
    }

    function processRequest(request, response) {
        if (shouldInterceptFile(request.request, response)) {
            var file = getAttachedFile(response);
            if (!file) {
                file = getFileFromUrl(response.url);
            }
            sendToXDM(request.request, response, file, false);
            return { redirectUrl: "javascript:" };
        } else {
            checkForVideo(request, response);
        }
    };

    function addToValueList(dict, key, value) {
        var values = dict[key];
        if (values) {
            values.push(value);
        }
        dict[key] = [value];
    }

    function arrayBufferToBase64(buffer) {
        var binary = '';
        var bytes = new Uint8Array(buffer);
        var len = bytes.byteLength;
        for (var i = 0; i < len; i++) {
            binary += String.fromCharCode(bytes[i]);
        }
        return window.btoa(binary);
    }

    function sendToXDM(request, response, file, video, requestBody) {
        log("sending to xdm: " + response.url);
        if (hasNativeMessagingHost) {
            var data = {
                url: response.url,
                file: file,
                requestHeaders: {},
                responseHeaders: {},
                cookies: {},
                method: request.method
            };
            if (requestBody) {
                data.requestBody = arrayBufferToBase64(requestBody);
            }
            if (request.extraHeaders) {
                request.extraHeaders.forEach(header => {
                    addToValueList(data.requestHeaders, header.name, header.value);
                });
            }
            if (request.requestHeaders) {
                request.requestHeaders.forEach(header => {
                    addToValueList(data.requestHeaders, header.name, header.value);
                });
            }
            if (response.responseHeaders) {
                response.responseHeaders.forEach(header => {
                    addToValueList(data.responseHeaders, header.name, header.value);
                });
            }
            addToValueList(data.responseHeaders, "tabId", request.tabId);
            addToValueList(data.responseHeaders, "realUA", navigator.userAgent);
            // chrome.cookies.getAll({ "url": response.url }, function (cookies) {
            //     cookies.forEach(cookie => {
            //         data.cookies[cookie.name] = cookie.value;
            //     });
            //     postNativeMessage({ messageType: video ? "video" : "download", message: data });
            // });
            postNativeMessage({ messageType: video ? "video" : "download", message: data });
            //port.postMessage({ "message": (video ? "/video" : "/download") + "\r\n" + data });
        } else {
            var data = "url=" + response.url + "\r\n";
            if (file) {
                data += "file=" + file + "\r\n";
            }
            if (requestBody) {
                data += "requestBody=" + arrayBufferToBase64(requestBody) + "\r\n";
            }
            if (request.extraHeaders) {
                for (var i = 0; i < request.extraHeaders.length; i++) {
                    data += "req=" + request.extraHeaders[i].name + ":" + request.extraHeaders[i].value + "\r\n";
                    console.log("extraHeaders: " + request.extraHeaders[i].name + ":" + request.extraHeaders[i].value);
                }
            }
            if (request.requestHeaders) {
                for (var i = 0; i < request.requestHeaders.length; i++) {
                    data += "req=" + request.requestHeaders[i].name + ":" + request.requestHeaders[i].value + "\r\n";
                    console.log("extraHeaders: " + request.requestHeaders[i].name + ":" + request.requestHeaders[i].value);
                }
            }

            for (var i = 0; i < response.responseHeaders.length; i++) {
                data += "res=" + response.responseHeaders[i].name + ":" + response.responseHeaders[i].value + "\r\n";
            }
            data += "res=tabId:" + request.tabId + "\r\n";
            data += "res=realUA:" + navigator.userAgent + "\r\n";
            chrome.cookies.getAll({ "url": response.url }, function (cookies) {
                for (var i = 0; i < cookies.length; i++) {
                    var cookie = cookies[i];
                    data += "cookie=" + cookie.name + ":" + cookie.value + "\r\n";
                }
                log(data);
                var xhr = new XMLHttpRequest();
                xhr.open('POST', xdmHost + (video ? "/video" : "/download"), true);
                xhr.send(data);
            });
        }
    };

    function sendRecUrl(urls, index, data) {
        if (index > 0 && index == urls.length - 1) {
            log(data);
            if (hasNativeMessagingHost) {
                log("Sending links to native host");
                log(data);
                postNativeMessage({ messageType: "links", messages: data });
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
                xhr.open('POST', xdmHost + "/links", true);
                xhr.send(text);
            }
            return;
        }

        let url = urls[index];
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
            sendRecUrl(urls, index + 1, data);
        });
    };

    function sendUrlsToXDM(urls) {
        if (urls && urls.length > 0) {
            sendRecUrl(urls, 0, []);
        }
    };

    function sendUrlToXDM(url) {
        log("sending to xdm: " + url);
        if (hasNativeMessagingHost) {
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
                log(data);
                postNativeMessage({ messageType: "download", message: data });
            });
        } else {
            var data = "url=" + url + "\r\n";
            data += "res=realUA:" + navigator.userAgent + "\r\n";
            chrome.cookies.getAll({ "url": url }, function (cookies) {
                for (var i = 0; i < cookies.length; i++) {
                    var cookie = cookies[i];
                    data += "cookie=" + cookie.name + ":" + cookie.value + "\r\n";
                }
                log(data);
                var xhr = new XMLHttpRequest();
                xhr.open('POST', xdmHost + "/download", true);
                xhr.send(data);
            });
        }
    };

    function sendImageToXDM(info, tab) {
        if (info.mediaType) {
            if ("image" == info.mediaType) {
                if (info.srcUrl) {
                    url = info.srcUrl;
                }
            }
        }

        if (!url) {
            url = info.linkUrl;
        }
        if (!url) {
            url = info.pageUrl;
        }
        if (!url) {
            return;
        }
        sendUrlToXDM(url);
    };

    function sendLinkToXDM(info, tab) {
        var url = info.linkUrl;
        if (!url) {
            if (info.mediaType) {
                if ("video" == info.mediaType || "audio" == info.mediaType) {
                    if (info.srcUrl) {
                        url = info.srcUrl;
                    }
                }
            }
        }
        if (!url) {
            url = info.pageUrl;
        }
        if (!url) {
            return;
        }
        sendUrlToXDM(url);
    };

    function runContentScript(info, tab) {
        log("running content script");
        chrome.tabs.executeScript({
            file: 'contentscript.js'
        });
    };

    function isVideoMime(mimeText) {
        if (!mimeList) {
            return false;
        }
        var mime = mimeText.toLowerCase();
        for (var i = 0; i < mimeList.length; i++) {
            if (mime.indexOf(mimeList[i]) != -1) {
                return true;
            }
        }
        return false;
    }

    function checkForVideo(request, response) {
        var mime = "";
        var video = false;
        var url = response.url;
        var hasRequestBody = false;

        for (var i = 0; i < response.responseHeaders.length; i++) {
            if (response.responseHeaders[i].name.toLowerCase() == "content-type") {
                mime = response.responseHeaders[i].value.toLocaleLowerCase();
                break;
            }
        }

        if (mime.startsWith("audio/") || mime.startsWith("video/") ||
            mime.indexOf("mpegurl") > 0 || mime.indexOf("f4m") > 0 || isVideoMime(mime)) {
            log("Checking video mime: " + mime + " " + JSON.stringify(mimeList));
            video = true;
        }

        if (!video && videoUrls) {
            for (var i = 0; i < videoUrls.length; i++) {
                var arr = videoUrls[i].split("|");
                var matched = true;
                for (var j = 0; j < arr.length; j++) {
                    //console.log(arr[j]);
                    if (url.indexOf(arr[j]) < 0) {
                        matched = false;
                        break;
                    }
                }
                if (matched) {
                    video = true;
                    log(url)
                    break;
                }
            }
        }

        if (!video && videoUrlsWithPostReq && request.request.method === "POST") {
            for (var i = 0; i < videoUrlsWithPostReq.length; i++) {
                if (url.indexOf(videoUrlsWithPostReq[i]) >= 0) {
                    video = true;
                    hasRequestBody = true;
                    break;
                }
            }
        }

        if (!video && vidExts) {
            var file = getFileFromUrl(url);
            var ext = getFileExtension(file);
            if (ext) {
                ext = ext.toUpperCase();
            }
            for (var i = 0; i < vidExts.length; i++) {
                if (vidExts[i] == ext) {
                    video = true;
                    break;
                }
            }
        }

        if (video) {
            var requestBody;
            if (hasRequestBody) {
                try {
                    requestBody = request.preRequest.requestBody.raw[0].bytes;
                    log("requestbody: " + requestBody);
                } catch (error) {
                    log(error);
                }
            }
            if (request.request.tabId != -1) {
                //give some time to browser to actually render the tab, so that we can grab the proper title
                window.setTimeout(() => {
                    chrome.tabs.get(
                        request.request.tabId,
                        function (tab) {
                            sendToXDM(request.request, response, tab.title, true, requestBody);
                        }
                    );
                }, 2000);
            } else {
                sendToXDM(request.request, response, null, true, requestBody);
            }
        }
    };

    function getAttachedFile(response) {
        for (var i = 0; i < response.responseHeaders.length; i++) {
            if (response.responseHeaders[i].name.toLowerCase() == 'content-disposition') {
                return getFileFromContentDisposition(response.responseHeaders[i].value);
            }
        }
    };

    function isHtmlOrScript(response) {
        for (var i = 0; i < response.responseHeaders.length; i++) {
            var name = response.responseHeaders[i].name.toLowerCase();
            if (name == 'content-type') {
                var contentType = response.responseHeaders[i].value;
                if (isBlockedMime(contentType)) {
                    return true;
                }
            }
        }
        return false;
    };

    function shouldInterceptFile(request, response) {
        var url = response.url;
        var isAttachment = false;
        if (isBlocked(url) || isHtmlOrScript(response) || request.method === "POST") {
            console.log("blocked: " + url);
            return false;
        }
        var file = getAttachedFile(response);
        if (!file) {
            file = getFileFromUrl(url);
        } else {
            isAttachment = true;
        }
        var ext = getFileExtension(file);
        if (ext) {
            if (!isAttachment) {
                for (var i = 0; i < vidExts.length; i++) {
                    if (vidExts[i] == ext.toUpperCase()) {
                        return false;
                    }
                }
            }
            for (var i = 0; i < fileExts.length; i++) {
                if (fileExts[i] == ext.toUpperCase()) {
                    return true;
                }
            }
        }
    };

    function isBlocked(url) {
        for (var i = 0; i < blockedHosts.length; i++) {
            var hostName = parseUrl(url).hostname;
            if (hostName.indexOf(blockedHosts[i]) >= 0) {
                return true;
            }
        }
        return false;
    };

    function isBlockedMime(mimeType) {
        for (var i = 0; i < blockedMimeList.length; i++) {
            if (mimeType && mimeType.indexOf(blockedMimeList[i]) >= 0) {
                return true;
            }
        }
        return false;
    };

    function syncXDM() {
        var xhr = new XMLHttpRequest();
        xhr.onreadystatechange = function () {
            if (xhr.readyState == XMLHttpRequest.DONE) {
                if (xhr.status == 200) {
                    var data = JSON.parse(xhr.responseText);
                    monitoring = data.enabled;
                    blockedHosts = data.blockedHosts;
                    videoUrls = data.videoUrls;
                    fileExts = data.fileExts;
                    vidExts = data.vidExts;
                    isXDMUp = true;
                    videoList = data.vidList;
                    if (data.mimeList) {
                        mimeList = data.mimeList;
                    }
                    if (data.blockedMimeList) {
                        blockedMimeList = data.blockedMimeList;
                    }
                    updateBrowserAction();
                }
                else {
                    isXDMUp = false;
                    monitoring = false;
                    updateBrowserAction();
                }
            }
        };

        xhr.open('GET', xdmHost + "/sync", true);
        xhr.send(null);
    };

    function getFileFromUrl(str) {
        return parseUrl(str).pathname;
    };

    function getFileFromContentDisposition(str) {
        var arr = str.split(";");
        for (var i = 0; i < arr.length; i++) {
            var ln = arr[i].trim();
            if (ln.indexOf("filename=") != -1) {
                var arr2 = ln.split("=");
                return arr2[1].replace(/"/g, '').trim();
            }
        }
    };

    function getFileExtension(file) {
        var index = file.lastIndexOf(".");
        if (index > 0) {
            return file.substr(index + 1);
        }
    };

    function parseUrl(str) {
        var match = str.match(/^(https?\:)\/\/(([^:\/?#]*)(?:\:([0-9]+))?)([\/]{0,1}[^?#]*)(\?[^#]*|)(#.*|)$/);
        return match && {
            href: str,
            protocol: match[1],
            host: match[2],
            hostname: match[3],
            port: match[4],
            pathname: match[5],
            search: match[6],
            hash: match[7]
        }
    };

    function removeRequests(requestId) {
        var request = requests[requestId];
        var preRequest = preRequests[requestId];
        if (preRequest) {
            delete preRequests[requestId];
        }
        if (request) {
            delete requests[requestId];
            return { request: request, preRequest: preRequest };
        }
    };

    function updateBrowserAction() {
        if (!isXDMUp) {
            setBrowserActionPopUp("fatal.html");
            setBrowserActionIcon("icon_blocked.png");
            return;
        }
        setBrowserActionPopUp(monitoring ? "status.html" : "disabled.html");
        setBrowserActionIcon(monitoring && !disabled ? "icon.png" : "icon_disabled.png");

        if (videoList && videoList.length > 0) {
            chrome.browserAction.setBadgeText({ text: videoList.length + "" });
        } else {
            chrome.browserAction.setBadgeText({ text: "" });
        }
    };

    function setBrowserActionIcon(icon) {
        if (lastIcon == icon) {
            return;
        }
        chrome.browserAction.setIcon({ path: icon });
        lastIcon = icon;
    };

    function setBrowserActionPopUp(pop) {
        if (lastPopup == pop) {
            return;
        }
        chrome.browserAction.setPopup({ popup: pop });
        lastPopup = pop;
    };

    function onHeadersReceived(response) {
        var request = removeRequests(response.requestId);
        if (!isXDMUp) {
            return;
        }
        if (!(request && request.request)) {
            return;
        }
        if (!(isXDMUp && monitoring && !disabled)) {
            return;
        }
        if (response.statusCode !== 200 && response.statusCode !== 206) {
            return;
        }
        if (!response.url || response.url.startsWith(xdmHost)) {
            return;
        }

        return processRequest(request, response);
    }

    function connectToNativeMessagingHost() {
        try {
            log("Connecting to native messaging host: xdm_chrome.native_host");
            port = chrome.runtime.connectNative("xdm_chrome.native_host");
            log("Connected to native messaging host");
            port.onDisconnect.addListener(function () {
                log("Disconnected from native messaging host!");
                hasNativeMessagingHost = false;
                isXDMUp = false;
                updateBrowserAction();
                port = undefined;
            });
            port.onMessage.addListener((data) => {
                log(JSON.stringify(data));
                if (data.appExited) {
                    postNativeMessage({});
                    isXDMUp = false;
                    hasNativeMessagingHost = false;
                } else {
                    monitoring = data.enabled;
                    blockedHosts = data.blockedHosts;
                    videoUrls = data.videoUrls;
                    fileExts = data.fileExts;
                    vidExts = data.vidExts;
                    isXDMUp = true;
                    hasNativeMessagingHost = true;
                    videoList = data.vidList;
                    if (data.mimeList) {
                        mimeList = data.mimeList;
                    }
                    if (data.videoUrlsWithPostReq) {
                        videoUrlsWithPostReq = data.videoUrlsWithPostReq;
                    }
                }
                updateBrowserAction();
            });
            return true;
        } catch (err) {
            log(err);
            return false;
        }
    }

    function initSelf() {
        //This will add the request to request array for later use, 
        //the object is removed from array when request completes or fails
        chrome.webRequest.onBeforeRequest.addListener(
            function (info) {
                preRequests[info.requestId] = info;
            },
            { urls: ["http://*/*", "https://*/*"] },
            ["extraHeaders", "requestBody"]
        );

        chrome.webRequest.onSendHeaders.addListener(
            function (info) {
                requests[info.requestId] = info;
            },
            { urls: ["http://*/*", "https://*/*"] },
            ["requestHeaders", "extraHeaders"]
        );

        chrome.webRequest.onResponseStarted.addListener(
            function (info) {
                removeRequests(info.requestId);
            },
            { urls: ["http://*/*", "https://*/*"] }
        );

        chrome.webRequest.onErrorOccurred.addListener(
            function (info) {
                removeRequests(info.requestId);
            },
            { urls: ["http://*/*", "https://*/*"] }
        );

        //This will monitor and intercept files download if 
        //criteria matches and XDM is running
        //Use request array to get request headers
        chrome.webRequest.onHeadersReceived.addListener(
            onHeadersReceived,
            { urls: ["http://*/*", "https://*/*"] },
            ["blocking", "responseHeaders"]
        );

        chrome.runtime.onMessage.addListener(
            function (request, sender, sendResponse) {
                if (request.type === "links") {
                    sendUrlsToXDM(request.links);
                    sendResponse({ done: "done" });
                }
                else if (request.type === "stat") {
                    var resp = { isDisabled: disabled };
                    resp.list = videoList;
                    sendResponse(resp);
                }
                else if (request.type === "cmd") {
                    disabled = request.disable;
                    log("disabled " + disabled);
                }
                else if (request.type === "vid") {
                    if (hasNativeMessagingHost) {
                        postNativeMessage({ messageType: "videoIds", videoIds: [request.itemId + ""] });
                    } else {
                        var xhr = new XMLHttpRequest();
                        xhr.open('POST', xdmHost + "/item", true);
                        xhr.send(request.itemId);
                    }
                }
                else if (request.type === "clear") {
                    if (hasNativeMessagingHost) {
                        postNativeMessage({ messageType: "clear" });
                    }
                    else {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', xdmHost + "/clear", true);
                        xhr.send();
                    }
                }
                else if (request.type === "reconnect") {
                    if (!hasNativeMessagingHost) {
                        connectToNativeMessagingHost();
                    }
                }
            }
        );

        chrome.commands.onCommand.addListener(function (command) {
            if (isXDMUp && monitoring) {
                log("called")
                disabled = !disabled;
            }
        });

        chrome.contextMenus.create({
            title: "Download with XDM",
            contexts: ["link", "video", "audio"],
            onclick: sendLinkToXDM,
        });

        chrome.contextMenus.create({
            title: "Download Image with XDM",
            contexts: ["image"],
            onclick: sendImageToXDM,
        });

        chrome.contextMenus.create({
            title: "Download all links",
            contexts: ["all"],
            onclick: runContentScript,
        });


        /*
        On startup, connect to the "native" app.
        */
        if (!connectToNativeMessagingHost()) {
            //play nice with older XDM versions
            setInterval(function () { syncXDM(); }, 5000);
        }

    };

    initSelf();
    log("extension loaded");
})();

(function () {
    var requests = [];
    var blockedHosts = [];
    var videoUrls = [];
    var fileExts = [];
    var vidExts = [];
    var isXDMUp = true;
    var monitoring = true;
    var debug = false;
    var xdmHost = "http://127.0.0.1:9614";
    var disabled = false;
    var lastIcon;
    var lastPopup;
    var videoList = [];
    var mimeList = [];

    var log = function (msg) {
        if (debug) {
            try {
                log(msg);
            } catch (e) {
                log(e + "");
            }
        }
    }

    var processRequest = function (request, response) {
        if (shouldInterceptFile(request, response)) {
            var file = getAttachedFile(response);
            if (!file) {
                file = getFileFromUrl(response.url);
            }
            sendToXDM(request, response, file, false);
            return { redirectUrl: "http://127.0.0.1:9614/204" };
        } else {
            checkForVideo(request, response);
        }
    };

    var sendToXDM = function (request, response, file, video) {
        log("sending to xdm: " + response.url);
        var data = "url=" + response.url + "\r\n";
        if (file) {
            data += "file=" + file + "\r\n";
        }
        for (var i = 0; i < request.requestHeaders.length; i++) {
            data += "req=" + request.requestHeaders[i].name + ":" + request.requestHeaders[i].value + "\r\n";
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
    };

    var sendRecUrl = function (urls, index, data) {
        if (index == urls.length - 1) {
            log(data);
            var xhr = new XMLHttpRequest();
            xhr.open('POST', xdmHost + "/links", true);
            xhr.send(data);
            return;
        }
        var url = urls[index];
        data += "url=" + url + "\r\n";
        data += "res=realUA:" + navigator.userAgent + "\r\n";
        chrome.cookies.getAll({ "url": url }, function (cookies) {
            for (var i = 0; i < cookies.length; i++) {
                var cookie = cookies[i];
                data += "cookie=" + cookie.name + ":" + cookie.value + "\r\n";
            }
            data += "\r\n\r\n";
            sendRecUrl(urls, index + 1, data);
        });
    };

    var sendUrlsToXDM = function (urls) {
        sendRecUrl(urls, 0, "");
    };

    var sendUrlToXDM = function (url) {
        log("sending to xdm: " + url);
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
    };

    var sendImageToXDM = function (info, tab) {
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

    var sendLinkToXDM = function (info, tab) {
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

    var runContentScript = function (info, tab) {
        log("running content script");
        chrome.tabs.executeScript({
            file: 'contentscript.js'
        });
    };

    var isVideoMime = function (mimeText) {
        if(!mimeList){
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

    var checkForVideo = function (request, response) {
        
        var mime = "";
        var video = false;
        var url = response.url;

        for (var i = 0; i < response.responseHeaders.length; i++) {
            if (response.responseHeaders[i].name.toLowerCase() == "content-type") {
                mime = response.responseHeaders[i].value.toLocaleLowerCase();
                break;
            }
        }

        

        if (mime.startsWith("audio/") || mime.startsWith("video/") ||
            mime.indexOf("mpegurl") > 0 || mime.indexOf("f4m") > 0 || isVideoMime(mime)) {
                log("Checking video mime: "+mime+" "+JSON.stringify(mimeList));
            video = true;
        }

        if (!video) {
            if (videoUrls) {
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
        }


        if (!video) {
            if (vidExts) {
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
        }

        if (video) {
            if (request.tabId != -1) {
                chrome.tabs.get
                    (
                    request.tabId,
                    function (tab) {
                        sendToXDM(request, response, tab.title, true);
                    }
                    );
            } else {
                sendToXDM(request, response, null, true);
            }
        }
    };

    var getAttachedFile = function (response) {
        for (var i = 0; i < response.responseHeaders.length; i++) {
            if (response.responseHeaders[i].name.toLowerCase() == 'content-disposition') {
                return getFileFromContentDisposition(response.responseHeaders[i].value);
            }
        }
    };

    var isHtml = function (response) {
        for (var i = 0; i < response.responseHeaders.length; i++) {
            if (response.responseHeaders[i].name.toLowerCase() == 'content-type') {
                return (response.responseHeaders[i].value.indexOf("text/html") != -1);
            }
        }
    };

    var shouldInterceptFile = function (request, response) {
        var url = response.url;
        var isAttachment = false;
        if (isBlocked(url)) {
            return false;
        }

        if (isHtml(response)) {
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

    var isBlocked = function (url) {
        for (var i = 0; i < blockedHosts.length; i++) {
            var hostName = parseUrl(url).hostname;
            if (blockedHosts[i] == hostName) {
                return true;
            }
        }
        return false;
    };

    var syncXDM = function () {
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

    var getFileFromUrl = function (str) {
        return ustr = parseUrl(str).pathname;
    };

    var getFileFromContentDisposition = function (str) {
        var arr = str.split(";");
        for (var i = 0; i < arr.length; i++) {
            var ln = arr[i].trim();
            if (ln.indexOf("filename=") != -1) {
                log("matching line: " + ln);
                var arr2 = ln.split("=");
                log("name: " + arr2[1]);
                return arr2[1].replace(/"/g, '').trim();
            }
        }
    };

    var getFileExtension = function (file) {
        var index = file.lastIndexOf(".");
        if (index > 0) {
            return file.substr(index + 1);
        }
    };

    var parseUrl = function (str) {
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

    var removeRequest = function (requestId) {
        for (var i = 0; i < requests.length; i++) {
            if (requests[i].requestId == requestId) {
                return requests.splice(i, 1);
            }
        }
    };

    var updateBrowserAction = function () {
        if (!isXDMUp) {
            setBrowserActionPopUp("fatal.html");
            setBrowserActionIcon("icon_blocked.png");
            return;
        }
        if (monitoring) {
            if (disabled) {
                setBrowserActionIcon("icon_disabled.png");
            } else {
                setBrowserActionIcon("icon.png");
            }
            setBrowserActionPopUp("status.html");
        } else {
            setBrowserActionIcon("icon_disabled.png");
            setBrowserActionPopUp("disabled.html");
        }

        if (videoList && videoList.length > 0) {
            chrome.browserAction.setBadgeText({ text: videoList.length + "" });
        } else {
            chrome.browserAction.setBadgeText({ text: "" });
        }
    };

    var setBrowserActionIcon = function (icon) {
        if (lastIcon == icon) {
            return;
        }
        chrome.browserAction.setIcon({ path: icon });
        lastIcon = icon;
    };

    var setBrowserActionPopUp = function (pop) {
        if (lastPopup == pop) {
            return;
        }
        chrome.browserAction.setPopup({ popup: pop });
        lastPopup = pop;
    };



    var initSelf = function () {
        //This will add the request to request array for later use, 
        //the object is removed from array when request completes or fails
        chrome.webRequest.onSendHeaders.addListener
            (
            function (info) { requests.push(info); },
            { urls: ["http://*/*", "https://*/*"] },
            ["requestHeaders"]
            );
        chrome.webRequest.onCompleted.addListener
            (
            function (info) {
                removeRequest(info.requestId);
            },
            { urls: ["http://*/*", "https://*/*"] }
            );

        chrome.webRequest.onErrorOccurred.addListener
            (
            function (info) {
                removeRequest(info.requestId);
            },
            { urls: ["http://*/*", "https://*/*"] }
            );

        //This will monitor and intercept files download if 
        //criteria matches and XDM is running
        //Use request array to get request headers
        chrome.webRequest.onHeadersReceived.addListener
            (
            function (response) {
                var requests = removeRequest(response.requestId);
                if (!isXDMUp) {
                    return;
                }

                if (!monitoring) {
                    return;
                }

                if (disabled) {
                    return;
                }

                if (!(response.statusLine.indexOf("200") > 0
                    || response.statusLine.indexOf("206") > 0)) {
                    return;
                }

                if (requests) {
                    if (requests.length == 1) {
                        if (!(response.url + "").startsWith(xdmHost)) {
                            //console.log("processing request " + response.url);
                            return processRequest(requests[0], response);
                        }
                    }
                }
            },
            { urls: ["http://*/*", "https://*/*"] },
            ["blocking", "responseHeaders"]
            );

        //check XDM if is running and enable monitoring
        setInterval(function () { syncXDM(); }, 5000);

        chrome.runtime.onMessage.addListener(
            function (request, sender, sendResponse) {
                if (request.type === "links") {
                    var arr = [];
                    arr = request.links;
                    /* for (var i = 0; i < arr.length; i++) {
                        console.log("link " + arr[i]);
                    } */
                    sendUrlsToXDM(arr);
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
                    var xhr = new XMLHttpRequest();
                    xhr.open('POST', xdmHost + "/item", true);
                    xhr.send(request.itemId);
                }
                else if (request.type === "clear") {
                    var xhr = new XMLHttpRequest();
                    xhr.open('GET', xdmHost + "/clear", true);
                    xhr.send();
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
    };

    initSelf();
    log("loaded");
})();

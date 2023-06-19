"use strict";
(function () {
    let arr = [], l = document.images;
    for (let i = 0; i < l.length; i++) {
        if (l[i].src && l[i].src.indexOf("http") === 0) {
            arr.push(l[i].src);
        }
    }
    chrome.runtime.sendMessage({
        type: "links",
        links: arr,
        pageUrl: document.location.href
    });
})();
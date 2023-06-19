"use strict";
(function () {
    let arr = [], l = document.links;
    for (let i = 0; i < l.length; i++) {
        if (l[i].href && l[i].href.indexOf("http") === 0) {
            arr.push(l[i].href);
        }
    }
    chrome.runtime.sendMessage({
        type: "links",
        links: arr,
        pageUrl: document.location.href
    });
})();
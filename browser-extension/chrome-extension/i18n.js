"use strict";

/**
 * Lightweight helper to internationalize HTML pages using WebExtension i18n API.
 * - data-i18n="messageKey": replaces textContent with chrome.i18n.getMessage(messageKey)
 * - data-i18n-attr="title:key1,placeholder:key2,aria-label:key3": sets element attributes
 */
(function () {
    const api = typeof chrome !== "undefined" && chrome.i18n
        ? chrome.i18n
        : (typeof browser !== "undefined" && browser.i18n ? browser.i18n : null);

    if (!api || typeof api.getMessage !== "function") {
        return;
    }

    function localizeElement(el) {
        if (el.dataset.i18n) {
            const msg = api.getMessage(el.dataset.i18n);
            if (msg) {
                el.textContent = msg;
            }
        }
        if (el.dataset.i18nAttr) {
            const pairs = el.dataset.i18nAttr.split(",");
            for (const pair of pairs) {
                const sep = pair.indexOf(":");
                if (sep > 0) {
                    const attr = pair.slice(0, sep).trim();
                    const key = pair.slice(sep + 1).trim();
                    const msg = api.getMessage(key);
                    if (msg) {
                        el.setAttribute(attr, msg);
                    }
                }
            }
        }
    }

    function localizeDocument() {
        document.querySelectorAll("[data-i18n], [data-i18n-attr]").forEach(localizeElement);
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", localizeDocument, { once: true });
    } else {
        localizeDocument();
    }

    window.localizeElement = localizeElement;
    window.localizeDocument = localizeDocument;
    window.t = function (key, substitutions) {
        return api.getMessage(key, substitutions) || key;
    };
})();

"use strict";

/**
 * Shown instead of the usual popup while Chrome withholds the extension's host permissions (the
 * "On click" and "On specific sites" site-access settings). The permission is a required one, so
 * it can be asked for again from here; the background script notices the grant through
 * permissions.onAdded and switches the popup back.
 */
window.onload = function () {
    document.getElementById("allow").addEventListener('click', function () {
        chrome.permissions.request({ origins: ["*://*/*"] }, function (granted) {
            if (chrome.runtime.lastError || !granted) {
                document.getElementById("detail").textContent =
                    (typeof chrome !== "undefined" && chrome.i18n && chrome.i18n.getMessage("site_access_denied")) ||
                    ("Access was not granted. You can also change it under Site access on the " +
                    "extension's details page (chrome://extensions).");
                return;
            }
            window.close();
        });
    });
};

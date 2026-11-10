window.onload = function () {
    document.getElementById("OpenLink").addEventListener('click', function () {
        window.open("xdm-app://launch");
        window.close();
    });
    // Asking for state makes the background script look for XDM again. If it is up by now - the
    // usual case: the user started it and came straight back here - switch this page over to
    // saying so, rather than leaving a stale "not running" behind (in a warning colour, no less).
    chrome.runtime.sendMessage({ type: "stat" }, function (response) {
        if (chrome.runtime.lastError || !response || !response.connected) {
            return;
        }
        const api = typeof chrome !== "undefined" && chrome.i18n ? chrome.i18n : (typeof browser !== "undefined" ? browser.i18n : null);
        document.getElementById("notice").className = "notice ok";
        document.getElementById("status").textContent = (api && api.getMessage("error_status_connected")) || "Connected to XDM";
        document.getElementById("detail").textContent =
            (api && api.getMessage("error_detail_connected")) || "Click the XDM icon again to see what has been detected on this page.";
        document.getElementById("actions").hidden = true;
    });
};

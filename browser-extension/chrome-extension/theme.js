"use strict";

// Shared by every Chrome extension page; the system theme is used until a choice is saved.
window.XdmTheme = (() => {
    const key = "theme";
    const system = window.matchMedia("(prefers-color-scheme: dark)");
    let preference = null;

    function apply(value) {
        preference = value === "dark" || value === "light" ? value : null;
        if (preference) {
            document.documentElement.dataset.theme = preference;
        } else {
            delete document.documentElement.dataset.theme;
        }
        window.dispatchEvent(new Event("xdm-theme-change"));
    }

    const ready = chrome.storage.local.get(key).then(stored => apply(stored && stored[key]))
        .catch(() => apply(null));

    chrome.storage.onChanged?.addListener((changes, area) => {
        if (area === "local" && changes[key]) {
            apply(changes[key].newValue);
        }
    });
    system.addEventListener("change", () => {
        if (!preference) window.dispatchEvent(new Event("xdm-theme-change"));
    });

    return {
        ready,
        isDark: () => preference ? preference === "dark" : system.matches,
        setDark(dark) {
            const value = dark ? "dark" : "light";
            apply(value);
            return chrome.storage.local.set({ [key]: value });
        }
    };
})();

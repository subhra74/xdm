"use strict";
import { collectPage } from './collector.js';
import { CATEGORIES, classify, compileFilter } from './classify.js';
import { isBlockedUrl } from './blocked-sites.js';

/**
 * The bulk download picker: one window per tab, opened from the popup, the context menu or the
 * keyboard command (App.openBulkPicker). It scans the tab once on open, lets the user narrow the
 * list down, and hands the chosen URLs to XDM - or to the clipboard, for an XDM without /batch.
 *
 * Every string shown here comes from a web page, so the list is built with textContent only.
 */

const ROW_HEIGHT = 52;
const OVERSCAN = 8;
const PREFS_KEY = "bulkPrefs";
const OBSERVER_SCRIPT_ID = "xdm-rt-observer";
const RELOAD_TIMEOUT_MS = 30000;
/** How often to look for XDM again while it isn't running, so starting it enables Send. */
const STATUS_RETRY_MS = 5000;
/** Late resources (lazy images, deferred fonts) still arrive after "complete". */
const SETTLE_AFTER_LOAD_MS = 1500;

const DEFAULT_CHIPS = {
    links: ["image", "video", "audio", "document", "archive", "program"],
    resources: ["image", "video", "audio", "document", "archive", "program"]
};

const SOURCE_LABELS = {
    "a": "link", "a-param": "link param", "onclick": "onclick", "img": "img", "srcset": "srcset",
    "css-bg": "css", "lazy": "lazy", "poster": "poster", "media": "media", "object": "object",
    "icon": "icon", "og": "meta", "rt": "network"
};

const params = new URLSearchParams(location.search);
const tabId = parseInt(params.get("tab"), 10);
const mode = params.get("mode") === "selection" ? "selection" : "all";

const state = {
    items: [],
    view: [],
    checked: new Set(),
    tab: "links",
    chips: { links: new Set(DEFAULT_CHIPS.links), resources: new Set(DEFAULT_CHIPS.resources) },
    filterText: "",
    filter: () => true,
    minSize: 0,
    noise: false,
    previews: false,
    connected: false,
    pageUrl: "",
    pageTitle: "",
    anchor: -1,
    scanning: false,
    /** The tab is on a site the extension never reads (blocked-sites.js). */
    blocked: false
};

const $ = id => document.getElementById(id);

// ---- startup ---------------------------------------------------------------

document.addEventListener("DOMContentLoaded", async () => {
    if (!(tabId >= 0)) {
        showFatal("No page to read.");
        return;
    }
    await loadPrefs();
    bindControls();
    renderTabs();
    renderChips();
    refreshXdmStatus();
    scan();
});

async function loadPrefs() {
    try {
        const stored = await chrome.storage.local.get(PREFS_KEY);
        const prefs = stored && stored[PREFS_KEY];
        if (!prefs) {
            return;
        }
        if (mode !== "selection" && (prefs.tab === "links" || prefs.tab === "resources")) {
            state.tab = prefs.tab;
        }
        if (prefs.chips) {
            for (const key of ["links", "resources"]) {
                if (Array.isArray(prefs.chips[key])) {
                    state.chips[key] = new Set(prefs.chips[key]);
                }
            }
        }
        state.minSize = Math.max(0, parseInt(prefs.minSize, 10) || 0);
        state.noise = prefs.noise === true;
        state.previews = prefs.previews === true;
    } catch (e) {
        // Preferences are a convenience; the defaults are fine.
    }
}

let savePrefsTimer = 0;

/** Remembers how the user likes to filter. Never URLs or anything else from the page. */
function savePrefs() {
    clearTimeout(savePrefsTimer);
    savePrefsTimer = setTimeout(() => {
        const prefs = {
            tab: state.tab,
            chips: { links: [...state.chips.links], resources: [...state.chips.resources] },
            minSize: state.minSize,
            noise: state.noise,
            previews: state.previews
        };
        try {
            chrome.storage.local.set({ [PREFS_KEY]: prefs });
        } catch (e) {
            // ignore
        }
    }, 300);
}

function bindControls() {
    document.querySelectorAll(".segment").forEach(btn => {
        btn.addEventListener("click", () => {
            state.tab = btn.dataset.tab;
            state.anchor = -1;
            savePrefs();
            renderTabs();
            renderChips();
            applyFilters();
        });
    });

    const filter = $("filter");
    filter.addEventListener("input", () => {
        const compiled = compileFilter(filter.value);
        filter.closest(".search").classList.toggle("invalid", compiled === null);
        if (compiled === null) {
            return;
        }
        state.filterText = filter.value;
        state.filter = compiled;
        applyFilters();
    });

    const minSize = $("minSize");
    minSize.value = state.minSize;
    minSize.addEventListener("input", () => {
        state.minSize = Math.max(0, parseInt(minSize.value, 10) || 0);
        savePrefs();
        applyFilters();
    });

    const noise = $("noise");
    noise.checked = state.noise;
    noise.addEventListener("change", () => {
        state.noise = noise.checked;
        savePrefs();
        renderChips();
        applyFilters();
    });

    const previews = $("previews");
    previews.checked = state.previews;
    previews.addEventListener("change", () => {
        state.previews = previews.checked;
        savePrefs();
        renderRows(true);
    });

    $("rescan").addEventListener("click", () => scan());
    $("reloadCapture").addEventListener("click", () => reloadAndCapture());

    $("bannerClose").addEventListener("click", hideBanner);

    // Ticks everything shown, or clears it when everything shown is already ticked.
    $("master").addEventListener("change", () => setAllShown(chosenShown().length < state.view.length));
    $("selInvert").addEventListener("click", () => {
        state.view.forEach(item => {
            if (state.checked.has(item.url)) {
                state.checked.delete(item.url);
            } else {
                state.checked.add(item.url);
            }
        });
        refreshChecks();
    });

    $("copy").addEventListener("click", copyUrls);
    $("send").addEventListener("click", sendToXdm);

    const list = $("list");
    let scheduled = false;
    list.addEventListener("scroll", () => {
        if (scheduled) {
            return;
        }
        scheduled = true;
        requestAnimationFrame(() => {
            scheduled = false;
            renderRows();
        });
    });
    window.addEventListener("resize", () => renderRows(true));
    window.addEventListener("focus", () => state.connected || refreshXdmStatus());

    document.addEventListener("keydown", e => {
        const inField = e.target instanceof HTMLInputElement && e.target.type !== "checkbox";
        if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "a" && !inField) {
            e.preventDefault();
            setAllShown(true);
        } else if (e.key === "/" && !inField) {
            e.preventDefault();
            filter.focus();
        } else if (e.key === "Escape" && e.target === filter && filter.value) {
            e.preventDefault();
            filter.value = "";
            filter.dispatchEvent(new Event("input"));
        }
    });
}

function setAllShown(check) {
    state.view.forEach(item => check ? state.checked.add(item.url) : state.checked.delete(item.url));
    refreshChecks();
}

// ---- scanning --------------------------------------------------------------

async function scan() {
    if (state.scanning) {
        return;
    }
    state.scanning = true;
    setBusy("Scanning page…");
    hideBanner();
    try {
        let tab;
        try {
            tab = await chrome.tabs.get(tabId);
        } catch (e) {
            showFatal("The page this window was opened for has been closed.");
            return;
        }
        showPage(tab.title, tab.url);
        if (isBlockedUrl(tab.url)) {
            showBlockedSite();
            return;
        }

        let result;
        try {
            const results = await chrome.scripting.executeScript({
                target: { tabId: tabId },
                func: collectPage,
                args: [{ mode: mode }]
            });
            result = results && results[0] && results[0].result;
        } catch (e) {
            await explainScanFailure(e, tab);
            return;
        }
        if (!result) {
            showFatal("XDM couldn't read this page.");
            return;
        }
        showPage(result.title || tab.title, result.pageUrl || tab.url);
        state.pageUrl = result.pageUrl || tab.url;
        state.pageTitle = result.title || tab.title || "";
        state.items = result.items.map(classify).filter(Boolean);
        // Drop anything checked that the page no longer has.
        const present = new Set(state.items.map(item => item.url));
        state.checked.forEach(url => present.has(url) || state.checked.delete(url));

        if (result.rtFull) {
            showBanner("Some files may be missing: this page loaded more than the browser keeps a "
                + "record of.", true);
        } else if (result.truncated) {
            showBanner("This page has a very large number of links; only the first 10,000 are shown.", false);
        }
        renderChips();
        applyFilters();
    } finally {
        state.scanning = false;
        clearBusy();
    }
}

async function explainScanFailure(error, tab) {
    const url = (tab && tab.url) || "";
    if (!/^https?:/i.test(url) || /^https:\/\/chromewebstore\.google\.com\//i.test(url)
        || /^https:\/\/chrome\.google\.com\/webstore/i.test(url)) {
        showFatal("XDM can't read this kind of page. Browser pages, the Chrome Web Store and the "
            + "built-in PDF viewer are off limits to extensions.");
        return;
    }
    let hostAccess = true;
    try {
        hostAccess = await chrome.permissions.contains({ origins: ["*://*/*"] });
    } catch (e) {
        // assume it is there
    }
    if (!hostAccess) {
        showFatal("XDM doesn't have access to this site. Open the extension's menu in the toolbar "
            + "and allow it on all sites, then press Rescan.");
        return;
    }
    showFatal("XDM couldn't read this page: " + ((error && error.message) || error));
}

/**
 * Reloads the page with rt-observer.js registered for it, so the complete list of what it fetches
 * is recorded from the very start. The script is registered for this site only, only for this
 * reload, and is removed again whatever happens.
 */
async function reloadAndCapture() {
    let pattern;
    try {
        const tab = await chrome.tabs.get(tabId);
        if (isBlockedUrl(tab.url)) {
            showBlockedSite();
            return;
        }
        const u = new URL(tab.url);
        pattern = u.protocol + "//" + u.hostname + "/*";
    } catch (e) {
        showFatal("The page this window was opened for has been closed.");
        return;
    }
    setBusy("Reloading page and recording what it loads…");
    hideBanner();
    await unregisterObserver();
    try {
        await chrome.scripting.registerContentScripts([{
            id: OBSERVER_SCRIPT_ID,
            matches: [pattern],
            js: ["rt-observer.js"],
            runAt: "document_start",
            allFrames: false,
            persistAcrossSessions: false
        }]);
        await reloadTab();
        await delay(SETTLE_AFTER_LOAD_MS);
    } catch (e) {
        showBanner("Reload & capture failed: " + ((e && e.message) || e), false);
    } finally {
        await unregisterObserver();
        clearBusy();
    }
    await scan();
}

async function unregisterObserver() {
    try {
        await chrome.scripting.unregisterContentScripts({ ids: [OBSERVER_SCRIPT_ID] });
    } catch (e) {
        // not registered
    }
}

/** Reloads the tab and resolves once it has finished loading again (or after a timeout). */
function reloadTab() {
    return new Promise(resolve => {
        let sawLoading = false;
        const finish = () => {
            clearTimeout(timer);
            chrome.tabs.onUpdated.removeListener(onUpdated);
            chrome.tabs.onRemoved.removeListener(onRemoved);
            resolve();
        };
        const onUpdated = (id, info) => {
            if (id !== tabId) {
                return;
            }
            if (info.status === "loading") {
                sawLoading = true;
            } else if (info.status === "complete" && sawLoading) {
                finish();
            }
        };
        const onRemoved = id => id === tabId && finish();
        const timer = setTimeout(finish, RELOAD_TIMEOUT_MS);
        chrome.tabs.onUpdated.addListener(onUpdated);
        chrome.tabs.onRemoved.addListener(onRemoved);
        chrome.tabs.reload(tabId, {}, () => void chrome.runtime.lastError);
    });
}

function delay(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

// ---- filtering -------------------------------------------------------------

function itemsForTab(tab) {
    return state.items.filter(item => (tab === "links" ? item.isLink : item.isResource)
        && (state.noise || !item.noise));
}

function passesMinSize(item) {
    if (state.minSize <= 0 || item.category !== "image" || !(item.w > 0 && item.h > 0)) {
        return true;
    }
    return item.w >= state.minSize && item.h >= state.minSize;
}

/** Whether an item is listed on a tab under that tab's chips and the shared filters. */
function shownIn(item, tab) {
    return (tab === "links" ? item.isLink : item.isResource)
        && (state.noise || !item.noise)
        && state.chips[tab].has(item.category)
        && passesMinSize(item)
        && state.filter(item);
}

function applyFilters() {
    const chips = state.chips[state.tab];
    state.view = state.items.filter(item => shownIn(item, state.tab));
    state.anchor = -1;
    $("countLinks").textContent = formatCount(itemsForTab("links").length);
    $("countResources").textContent = formatCount(itemsForTab("resources").length);
    $("minSizeField").hidden = !chips.has("image");
    $("list").scrollTop = 0;
    renderList();
}

// ---- rendering -------------------------------------------------------------

function renderTabs() {
    document.querySelectorAll(".segment").forEach(btn => {
        btn.setAttribute("aria-selected", btn.dataset.tab === state.tab ? "true" : "false");
    });
}

function renderChips() {
    const container = $("chips");
    container.textContent = "";
    const counts = new Map();
    itemsForTab(state.tab).forEach(item => counts.set(item.category, (counts.get(item.category) || 0) + 1));
    const chips = state.chips[state.tab];
    for (const cat of CATEGORIES) {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "chip";
        btn.dataset.cat = cat.id;
        btn.classList.toggle("empty", !counts.get(cat.id));
        btn.setAttribute("aria-pressed", chips.has(cat.id) ? "true" : "false");
        const dot = document.createElement("span");
        dot.className = "dot";
        const label = document.createElement("span");
        label.textContent = cat.label;
        const count = document.createElement("span");
        count.className = "chip-count";
        count.textContent = formatCount(counts.get(cat.id) || 0);
        btn.append(dot, label, count);
        btn.addEventListener("click", () => {
            if (chips.has(cat.id)) {
                chips.delete(cat.id);
            } else {
                chips.add(cat.id);
            }
            btn.setAttribute("aria-pressed", chips.has(cat.id) ? "true" : "false");
            savePrefs();
            applyFilters();
        });
        container.appendChild(btn);
    }
}

function renderList() {
    $("spacer").style.height = (state.view.length * ROW_HEIGHT) + "px";
    const empty = $("empty");
    empty.hidden = state.view.length > 0 || state.scanning;
    if (state.items.length === 0) {
        $("emptyText").textContent = mode === "selection"
            ? "No links or images in the selected part of the page."
            : "Nothing to download was found on this page.";
    } else {
        $("emptyText").textContent = "Nothing matches the current filters.";
    }
    renderRows(true);
    renderSummary();
}

let renderedRange = "";

/**
 * Only the rows in view (plus a margin) exist. Scrolling within the same range leaves them alone,
 * so thumbnails are not torn down and reloaded on every scroll event.
 */
function renderRows(force) {
    const list = $("list");
    const spacer = $("spacer");
    const first = Math.max(0, Math.floor(list.scrollTop / ROW_HEIGHT) - OVERSCAN);
    const last = Math.min(state.view.length, Math.ceil((list.scrollTop + list.clientHeight) / ROW_HEIGHT) + OVERSCAN);
    const range = first + ":" + last;
    if (!force && range === renderedRange) {
        return;
    }
    renderedRange = range;
    const template = $("rowTemplate");
    spacer.textContent = "";
    for (let i = first; i < last; i++) {
        spacer.appendChild(buildRow(template, state.view[i], i));
    }
}

function buildRow(template, item, index) {
    const row = template.content.firstElementChild.cloneNode(true);
    row.style.top = (index * ROW_HEIGHT) + "px";
    row.dataset.index = index;
    const checked = state.checked.has(item.url);
    row.classList.toggle("checked", checked);
    row.querySelector(".row-check").checked = checked;
    row.querySelector(".row-name").textContent = nameLine(item);
    row.querySelector(".row-url").textContent = shortUrl(item.url);
    row.title = item.url;
    row.querySelector(".row-thumb").dataset.cat = item.category;
    row.querySelector(".row-info").textContent = infoOf(item);
    row.querySelector(".row-source").textContent = item.sources.map(s => SOURCE_LABELS[s] || s).join(", ");
    fillThumb(row.querySelector(".row-thumb"), item, row);
    row.addEventListener("click", e => toggleRow(index, e.shiftKey));
    return row;
}

/** The URL without its scheme: the row is narrow, and the full address is in the tooltip. */
function shortUrl(url) {
    return url.replace(/^https?:\/\//i, "");
}

/** The file name, plus the link text when that says something the name doesn't. */
function nameLine(item) {
    const text = state.tab === "links" && item.isLink ? (item.text || "") : "";
    if (!text || text.toLowerCase().includes(item.filename.toLowerCase())) {
        return text && !item.filename ? text : item.filename;
    }
    return item.filename + " — " + text;
}

/**
 * Whether a row may show a picture. A preview is a fresh request from this extension page - the
 * page's cached copy is not shared with it - so previews are opt-in, and never made for an image the
 * page itself did not load (a lazy image still waiting) or for a 1-2 px tracking pixel, which would
 * just register another hit.
 */
function canPreview(item) {
    if (!state.previews || item.category !== "image" || !item.loaded || item.thumbFailed) {
        return false;
    }
    return !(item.w > 0 && item.h > 0 && (item.w <= 2 || item.h <= 2));
}

function fillThumb(thumb, item, row) {
    const fallback = () => {
        thumb.textContent = (item.ext || item.category || "").slice(0, 4);
    };
    if (!canPreview(item)) {
        fallback();
        return;
    }
    const img = document.createElement("img");
    img.loading = "lazy";
    img.decoding = "async";
    img.alt = "";
    img.addEventListener("load", () => {
        if (!(item.w > 0) && img.naturalWidth > 0) {
            item.w = img.naturalWidth;
            item.h = img.naturalHeight;
            row.querySelector(".row-info").textContent = infoOf(item);
        }
    });
    img.addEventListener("error", () => {
        item.thumbFailed = true;
        img.remove();
        fallback();
    });
    img.src = item.url;
    thumb.appendChild(img);
}

function infoOf(item) {
    const parts = [];
    if (item.w > 0 && item.h > 0) {
        parts.push(item.w + "×" + item.h);
    }
    if (item.size > 0) {
        parts.push(formatBytes(item.size));
    }
    return parts.join(" · ");
}

function toggleRow(index, range) {
    const item = state.view[index];
    if (!item) {
        return;
    }
    const check = !state.checked.has(item.url);
    if (range && state.anchor >= 0 && state.anchor < state.view.length) {
        const [from, to] = state.anchor < index ? [state.anchor, index] : [index, state.anchor];
        for (let i = from; i <= to; i++) {
            if (check) {
                state.checked.add(state.view[i].url);
            } else {
                state.checked.delete(state.view[i].url);
            }
        }
    } else if (check) {
        state.checked.add(item.url);
    } else {
        state.checked.delete(item.url);
    }
    state.anchor = index;
    refreshChecks();
}

/** Re-ticks the rows already on screen, leaving their thumbnails alone. */
function refreshChecks() {
    for (const row of $("spacer").children) {
        const item = state.view[+row.dataset.index];
        const checked = !!item && state.checked.has(item.url);
        row.classList.toggle("checked", checked);
        row.querySelector(".row-check").checked = checked;
    }
    renderSummary();
}

/** Ticked rows on the current tab. */
function chosenShown() {
    return state.view.filter(item => state.checked.has(item.url));
}

/**
 * What Send and Copy act on: ticked and listed on either tab under its filters, so links and
 * images & files go to XDM together. Items are unique by URL, so one listed on both tabs goes once.
 */
function chosenItems() {
    return state.items.filter(item => state.checked.has(item.url)
        && (shownIn(item, "links") || shownIn(item, "resources")));
}

function renderSummary() {
    const all = chosenItems();
    const chosen = all.length;
    const chosenHere = chosenShown().length;
    const shown = state.view.length;
    const master = $("master");
    master.checked = shown > 0 && chosenHere === shown;
    master.indeterminate = chosenHere > 0 && chosenHere < shown;
    master.disabled = shown === 0;
    $("selInvert").disabled = shown === 0;
    $("listLabel").textContent = chosenHere > 0
        ? formatCount(chosenHere) + " of " + formatCount(shown) + " selected"
        : "Select all " + formatCount(shown);

    // The footer's left side says what is selected - unless XDM is down, which matters more.
    const summary = $("summary");
    summary.textContent = "";
    if (chosen > 0) {
        const strong = document.createElement("strong");
        strong.textContent = formatCount(chosen);
        summary.append(strong, chosen === 1 ? " item ready" : " items ready");
        // Say so when the selection spans both tabs; an item listed on both counts as a link.
        const links = all.filter(item => shownIn(item, "links")).length;
        if (links > 0 && links < chosen) {
            summary.append(" (" + formatCount(links) + (links === 1 ? " link, " : " links, ")
                + formatCount(chosen - links) + " images & files)");
        }
    } else {
        summary.textContent = shown > 0 ? "Tick the files to download" : "";
    }
    summary.hidden = !state.connected;
    $("sendHint").hidden = state.connected;

    $("copy").disabled = chosen === 0;
    $("send").disabled = chosen === 0 || !state.connected;
    $("sendLabel").textContent = chosen > 0 ? "Download " + formatCount(chosen) : "Download";
}

/** The page is named in the window title only; the picker itself has no room to spare. */
function showPage(title, url) {
    document.title = "Download with XDM — " + (mode === "selection" ? "selected links on " : "") + (title || url || "");
}

function setBusy(text) {
    $("busyText").textContent = text;
    $("busy").hidden = false;
    $("rescan").disabled = true;
    $("reloadCapture").disabled = true;
}

function clearBusy() {
    $("busy").hidden = true;
    $("rescan").disabled = state.blocked;
    $("reloadCapture").disabled = state.blocked;
}

function showBanner(text, offerReload) {
    $("banner").classList.remove("warn");
    $("bannerText").textContent = text;
    $("reloadCapture").hidden = !offerReload;
    $("banner").hidden = false;
}

function hideBanner() {
    $("banner").hidden = true;
}

/** The page is one the extension never reads (blocked-sites.js). Rescanning won't change that. */
function showBlockedSite() {
    state.blocked = true;
    showFatal("XDM doesn't download from YouTube. Links and videos on this site are left to the browser.");
}

function showFatal(text) {
    state.items = [];
    state.view = [];
    showBanner(text, false);
    $("banner").classList.add("warn");
    renderList();
    $("empty").hidden = true;
}

let toastTimer = 0;

function toast(text) {
    const el = $("toast");
    el.textContent = text;
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
        el.hidden = true;
    }, 3500);
}

function formatCount(n) {
    return n.toLocaleString();
}

function formatBytes(n) {
    const units = ["B", "KB", "MB", "GB"];
    let i = 0;
    while (n >= 1024 && i < units.length - 1) {
        n /= 1024;
        i++;
    }
    return (i === 0 ? n : n.toFixed(n < 10 ? 1 : 0)) + " " + units[i];
}

// ---- handing over ----------------------------------------------------------

let statusTimer = 0;

/** Asks the service worker whether XDM is up; keeps asking every few seconds while it is not. */
function refreshXdmStatus() {
    clearTimeout(statusTimer);
    chrome.runtime.sendMessage({ type: "bulk-status" }, response => {
        state.connected = !chrome.runtime.lastError && !!response && response.connected === true;
        renderSummary();
        if (!state.connected) {
            statusTimer = setTimeout(refreshXdmStatus, STATUS_RETRY_MS);
        }
    });
}

async function copyUrls() {
    const urls = chosenItems().map(item => item.url);
    if (urls.length === 0) {
        return;
    }
    try {
        await navigator.clipboard.writeText(urls.join("\n"));
        toast("Copied " + formatCount(urls.length) + (urls.length === 1 ? " URL" : " URLs")
            + ". In XDM, choose “Add from clipboard”.");
    } catch (e) {
        toast("Couldn't copy to the clipboard: " + ((e && e.message) || e));
    }
}

function sendToXdm() {
    const items = chosenItems().map(item => ({
        url: item.url,
        // Only a name the page chose (download=""); otherwise XDM names the file as it always does.
        filename: item.name || undefined,
        mimeType: item.mime || undefined,
        fileSize: item.size > 0 ? item.size : undefined
    }));
    if (items.length === 0 || !state.connected) {
        return;
    }
    $("send").disabled = true;
    chrome.runtime.sendMessage({
        type: "batch",
        tabId: tabId,
        pageUrl: state.pageUrl,
        pageTitle: state.pageTitle,
        items: items
    }, response => {
        if (chrome.runtime.lastError || !response || !response.ok) {
            if (response && response.notRunning) {
                // XDM went away since the window opened: show the hint and watch for it again.
                refreshXdmStatus();
                return;
            }
            const reason = (response && response.error) || (chrome.runtime.lastError && chrome.runtime.lastError.message);
            toast("Couldn't send to XDM" + (reason ? ": " + reason : "."));
            renderSummary();
            return;
        }
        toast("Sent " + formatCount(response.count) + " to XDM.");
        setTimeout(() => window.close(), 900);
    });
}

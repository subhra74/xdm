"use strict";

/** Previews asked of XDM at once; matches the connection pool of its manifest client. */
const PREVIEW_CONCURRENCY = 5;
const PREVIEWS_KEY = "showPreviews";

/**
 * The toolbar popup: the videos XDM has detected in the active tab, and the monitoring and preview
 * toggles.
 *
 * Everything that comes from a web page (titles, sizes) is written with textContent, never
 * innerHTML - the list is built from page-supplied strings.
 */
class VideoPopup {
    constructor() {
        this.items = [];
        this.showPreviews = true;
        this.activePanel = null;
        /** Preview loads by item id, so a redrawn list does not ask XDM again. */
        this.loads = new Map();
        this.queue = [];
        this.running = 0;
    }

    run() {
        document.addEventListener('DOMContentLoaded', this.onLoad.bind(this), false);
    }

    onLoad() {
        const theme = document.getElementById("theme");
        const syncTheme = () => { theme.checked = XdmTheme.isDark(); };
        XdmTheme.ready.then(syncTheme);
        window.addEventListener("xdm-theme-change", syncTheme);
        theme.addEventListener("change", () => {
            const status = document.getElementById("settingsStatus");
            status.hidden = true;
            XdmTheme.setDark(theme.checked).catch(() => {
                status.textContent = "Theme changed for this window, but the preference could not be saved.";
                status.hidden = false;
            });
        });

        // The preference first, so the list is drawn once with or without previews.
        chrome.storage.local.get(PREVIEWS_KEY, stored => {
            // On unless the user turned it off.
            this.showPreviews = !!chrome.runtime.lastError || !stored || stored[PREVIEWS_KEY] !== false;
            document.getElementById("previews").checked = this.showPreviews;
            chrome.runtime.sendMessage({ type: "stat" }, this.onMsg.bind(this));
        });

        document.getElementById("previews").addEventListener('change', e => {
            this.showPreviews = e.target.checked;
            chrome.storage.local.set({ [PREVIEWS_KEY]: this.showPreviews });
            this.renderList(this.items);
        });

        document.getElementById("chk").addEventListener('change', e => {
            chrome.runtime.sendMessage({ type: "cmd", enabled: e.target.checked });
            window.close();
        });

        // The picker opens in a window of its own (App.openBulkPicker) for the tab behind the popup.
        document.getElementById('bulk').addEventListener('click', () => {
            chrome.tabs.query({ active: true, currentWindow: true }, tabs => {
                if (tabs && tabs[0]) {
                    chrome.runtime.sendMessage({ type: "bulk", tabId: tabs[0].id });
                }
                window.close();
            });
        });

        for (const id of ["clear", "format", "settings"]) {
            document.getElementById(id).addEventListener("click", () => this.togglePanel(id));
        }
        document.getElementById("cancelClear").addEventListener("click", () => this.closePanel(true));
        document.getElementById('confirmClear').addEventListener('click', () => {
            chrome.runtime.sendMessage({ type: "clear" });
            this.closePanel();
            this.setItems([]);
            document.getElementById("empty").focus();
        });

        document.addEventListener("keydown", e => {
            if (e.key === "Escape" && this.activePanel) {
                e.preventDefault();
                this.closePanel(true);
            }
        });
    }

    togglePanel(controlId) {
        const wasOpen = this.activePanel === controlId;
        this.closePanel();
        if (wasOpen) return;
        const control = document.getElementById(controlId);
        document.getElementById(control.getAttribute("aria-controls")).hidden = false;
        control.setAttribute("aria-expanded", "true");
        this.activePanel = controlId;
    }

    closePanel(restoreFocus = false) {
        if (!this.activePanel) return;
        const control = document.getElementById(this.activePanel);
        document.getElementById(control.getAttribute("aria-controls")).hidden = true;
        control.setAttribute("aria-expanded", "false");
        this.activePanel = null;
        if (restoreFocus) control.focus();
    }

    setItems(items) {
        this.items = items;
        this.renderList(items);
        document.getElementById("empty").hidden = items.length > 0;
        document.getElementById("clear").disabled = items.length === 0;
        document.getElementById("format").disabled = items.length === 0;
    }

    onMsg(response) {
        if (chrome.runtime.lastError || !response) {
            return;
        }
        document.getElementById("chk").checked = response.enabled === true;

        // Connected to an XDM older than the extension expects: suggest updating it.
        if (response.outdated) {
            if (response.xdmVersion) {
                document.getElementById("outdatedVersion").textContent = "XDM " + response.xdmVersion;
            }
            if (response.updateUrl) {
                document.getElementById("outdatedLink").href = response.updateUrl;
            }
            document.getElementById("outdated").hidden = false;
            // An older XDM has no previews to offer.
            document.getElementById("previewsToggle").hidden = true;
        }

        // YouTube (blocked-sites.js): nothing is captured there, so explain that instead of
        // inviting the user to play a video, and offer no bulk picker.
        if (response.blockedSite) {
            document.getElementById('blockedSite').hidden = false;
            document.getElementById('bulk').disabled = true;
            return;
        }

        this.setItems(response.list || []);
    }

    renderList(items) {
        const list = document.getElementById("list");
        const template = document.getElementById("rowTemplate");
        list.replaceChildren();
        // Newest capture first, matching the order the list used to be built in.
        items.slice().reverse().forEach(item => {
            const row = template.content.cloneNode(true);
            const button = row.querySelector('.video-item');
            const badge = row.querySelector('.format-badge');
            const meta = this.splitInfo(item.info);

            row.querySelector('.video-title').textContent = this.titleOf(item.text, meta.format);
            if (meta.format) {
                badge.textContent = meta.format;
                badge.hidden = false;
            }
            row.querySelector('.video-size').textContent = meta.rest;
            button.title = item.text;
            button.addEventListener('click', () => {
                chrome.runtime.sendMessage({ type: "vid", itemId: item.id });
                window.close();
            });
            // Only single MP4 files are previewable (XDM decides); never HLS or segmented DASH.
            if (item.previewable && this.showPreviews) {
                this.preview(item.id, row.querySelector('.thumb'));
            }
            list.appendChild(row);
        });
    }

    /**
     * The video's first keyframe in place of the icon (FramePreviews), so captures on a page with
     * many videos can be told apart. A preview kept from earlier is shown straight away; others are
     * asked of XDM, [PREVIEW_CONCURRENCY] at a time.
     */
    preview(id, thumb) {
        thumb.classList.add('loading');
        this.loadPreview(id).then(images => {
            thumb.classList.remove('loading');
            this.showPreview(thumb, images && images[0]);
        }).catch(err => {
            thumb.classList.remove('loading');
            thumb.title = "Preview unavailable: " + (err && err.message ? err.message : err);
        });
    }

    loadPreview(id) {
        let load = this.loads.get(id);
        if (!load) {
            load = FramePreviews.cached(id).then(images =>
                images && images.length ? images : this.enqueue(() => FramePreviews.load(id)));
            this.loads.set(id, load);
        }
        return load;
    }

    enqueue(task) {
        return new Promise((resolve, reject) => {
            this.queue.push({ task, resolve, reject });
            this.pump();
        });
    }

    pump() {
        while (this.running < PREVIEW_CONCURRENCY && this.queue.length) {
            const { task, resolve, reject } = this.queue.shift();
            this.running++;
            task().then(resolve, reject).finally(() => {
                this.running--;
                this.pump();
            });
        }
    }

    showPreview(thumb, image) {
        if (!image) {
            return;
        }
        const img = document.createElement('img');
        img.alt = "";
        img.src = image;
        thumb.replaceChildren(img);
        thumb.classList.add('has-preview');
    }

    /**
     * XDM describes a capture as something like "[mp4] 1.4 MB". Split the container off so it can
     * be shown as a badge - with several captures of the same page, the format and size are the
     * only things that tell them apart. Anything that doesn't match is shown as-is.
     */
    splitInfo(info) {
        const text = (info || "").trim();
        const match = /^\[([^\]]{1,12})\]\s*(.*)$/.exec(text);
        return match ? { format: match[1].trim(), rest: match[2].trim() } : { format: "", rest: text };
    }

    /** Drops the extension when the badge already says it, e.g. "clip.mp4" + [MP4] -> "clip". */
    titleOf(name, format) {
        const text = name || "";
        if (!format) {
            return text;
        }
        const suffix = "." + format.toLowerCase();
        return text.toLowerCase().endsWith(suffix) ? text.slice(0, -suffix.length) : text;
    }
}

var popup = new VideoPopup();
popup.run();

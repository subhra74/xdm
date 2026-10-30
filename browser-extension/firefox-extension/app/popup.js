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
        /** Preview loads by item id, so a redrawn list does not ask XDM again. */
        this.loads = new Map();
        this.queue = [];
        this.running = 0;
    }

    run() {
        document.addEventListener('DOMContentLoaded', this.onLoad.bind(this), false);
    }

    onLoad() {
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

        document.getElementById('clear').addEventListener('click', () => {
            chrome.runtime.sendMessage({ type: "clear" });
            window.close();
        });

        // Used to be an alert(), which is a jarring thing for a popup to do; the explanation now
        // expands in place.
        const format = document.getElementById('format');
        const hint = document.getElementById('formatHint');
        format.addEventListener('click', () => {
            const show = hint.hidden;
            hint.hidden = !show;
            format.setAttribute('aria-expanded', show ? 'true' : 'false');
        });
    }

    onMsg(response) {
        if (chrome.runtime.lastError || !response) {
            return;
        }
        document.getElementById("chk").checked = response.enabled === true;

        const list = response.list || [];
        this.items = list;
        this.renderList(list);

        document.getElementById('empty').hidden = list.length > 0;
        document.getElementById('actions').hidden = list.length === 0;
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

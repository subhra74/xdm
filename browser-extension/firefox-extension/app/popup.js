"use strict";

/**
 * The toolbar popup: the videos XDM has detected in the active tab, and the monitoring toggle.
 *
 * Everything that comes from a web page (titles, sizes) is written with textContent, never
 * innerHTML - the list is built from page-supplied strings.
 */
class VideoPopup {
    run() {
        document.addEventListener('DOMContentLoaded', this.onLoad.bind(this), false);
    }

    onLoad() {
        chrome.runtime.sendMessage({ type: "stat" }, this.onMsg.bind(this));

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
        this.renderList(list);

        const count = document.getElementById('count');
        count.textContent = list.length + "";
        count.hidden = list.length === 0;
        document.getElementById('empty').hidden = list.length > 0;
        document.getElementById('actions').hidden = list.length === 0;
    }

    renderList(items) {
        const list = document.getElementById("list");
        const template = document.getElementById("rowTemplate");
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
            if (item.previewable) {
                this.setUpPreview(item.id, row.querySelector('.thumb'), row.querySelector('.preview-button'));
            }
            list.appendChild(row);
        });
    }

    /**
     * The preview button of a captured MP4 (FramePreviews): XDM reads the video's first keyframe and
     * the popup decodes it into the thumbnail, so captures on a page with many videos can be told
     * apart. A preview kept from earlier is shown straight away.
     */
    setUpPreview(id, thumb, previewButton) {
        previewButton.hidden = false;
        previewButton.addEventListener('click', () => {
            previewButton.disabled = true;
            previewButton.classList.add('busy');
            previewButton.title = "Loading preview…";
            FramePreviews.load(id).then(images => {
                this.showPreview(thumb, previewButton, images[0]);
            }).catch(err => {
                previewButton.classList.remove('busy');
                previewButton.classList.add('failed');
                previewButton.title = "Preview unavailable: " + (err && err.message ? err.message : err);
            });
        });
        FramePreviews.cached(id).then(images => {
            if (images && images.length) {
                this.showPreview(thumb, previewButton, images[0]);
            }
        });
    }

    showPreview(thumb, previewButton, image) {
        if (!image) {
            return;
        }
        const img = document.createElement('img');
        img.alt = "";
        img.src = image;
        thumb.replaceChildren(img);
        thumb.classList.add('has-preview');
        previewButton.classList.remove('busy');
        previewButton.hidden = true;
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

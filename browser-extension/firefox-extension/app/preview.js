"use strict";

/**
 * Keyframe previews for captured MP4 files. XDM reads the file's first keyframe (`/preview`, one
 * range request at a time, with the headers it captured) and the popup decodes it here with
 * WebCodecs into a small JPEG. They are kept per item in storage.session, so reopening the popup
 * shows them without asking XDM again.
 *
 * The request goes straight to XDM rather than through the background connector: a slow or failed
 * preview must not look like XDM going away.
 */
const PREVIEW_URL = "http://127.0.0.1:8597/preview";
const PREVIEW_TIMEOUT_MS = 20000;
const PREVIEW_WIDTH = 160;

class FramePreviews {
    static key(id) {
        return "preview:" + id;
    }

    /** The JPEG data URLs kept for item [id], or null. */
    static async cached(id) {
        try {
            const key = FramePreviews.key(id);
            const stored = await chrome.storage.session.get(key);
            return stored[key] || null;
        } catch (e) {
            return null;
        }
    }

    /** Asks XDM for keyframes of item [id] and decodes them; resolves to JPEG data URLs in time order. */
    static async load(id) {
        const res = await fetch(PREVIEW_URL, {
            method: "POST",
            body: JSON.stringify({ vid: id + "" }),
            signal: AbortSignal.timeout(PREVIEW_TIMEOUT_MS)
        });
        if (!res.ok) {
            throw new Error("XDM answered " + res.status);
        }
        const reply = await res.json();
        if (reply.error) {
            throw new Error(reply.error);
        }
        const images = await FramePreviews.decode(reply);
        try {
            await chrome.storage.session.set({ [FramePreviews.key(id)]: images });
        } catch (e) {
            // Only a cache: the preview is shown either way.
        }
        return images;
    }

    static async decode(reply) {
        if (typeof VideoDecoder === "undefined") {
            throw new Error("This browser cannot decode video frames");
        }
        const config = { codec: reply.codec, codedWidth: reply.width, codedHeight: reply.height };
        if (reply.description) {
            config.description = FramePreviews.bytes(reply.description);
        }
        const support = await VideoDecoder.isConfigSupported(config);
        if (!support.supported) {
            throw new Error("This browser cannot decode " + reply.codec);
        }

        // Every chunk is a keyframe, so one decoder takes them all and one flush gets every frame out.
        const frames = [];
        const decoder = new VideoDecoder({ output: frame => frames.push(frame), error: () => { } });
        try {
            decoder.configure(config);
            for (const f of reply.frames) {
                decoder.decode(new EncodedVideoChunk({ type: "key", timestamp: f.timeUs, data: FramePreviews.bytes(f.data) }));
            }
            await decoder.flush();
        } catch (e) {
            // A frame that fails stops the decoder; keep the ones that came out before it.
            if (frames.length === 0) {
                throw new Error("Could not decode the video frames");
            }
        } finally {
            if (decoder.state !== "closed") {
                decoder.close();
            }
        }

        const images = [];
        for (const frame of frames) {
            try {
                images.push(await FramePreviews.toJpeg(frame));
            } finally {
                frame.close();
            }
        }
        return images;
    }

    static async toJpeg(frame) {
        const width = PREVIEW_WIDTH;
        const height = Math.max(1, Math.round(width * frame.displayHeight / frame.displayWidth));
        const canvas = new OffscreenCanvas(width, height);
        canvas.getContext("2d").drawImage(frame, 0, 0, width, height);
        const blob = await canvas.convertToBlob({ type: "image/jpeg", quality: 0.75 });
        return new Promise((resolve, reject) => {
            const reader = new FileReader();
            reader.onload = () => resolve(reader.result);
            reader.onerror = () => reject(reader.error);
            reader.readAsDataURL(blob);
        });
    }

    static bytes(base64) {
        return Uint8Array.from(atob(base64), c => c.charCodeAt(0));
    }
}

"use strict";

/**
 * Sorts what collectPage found into what the bulk picker shows. Evidence, strongest first: the
 * Content-Type Resource Timing reported (often empty for cross-origin entries), the URL's extension,
 * where in the page it was found, and finally who requested it.
 *
 * Scripts and stylesheets are never offered. API calls, frame documents and stream segments are
 * "noise": hidden unless the user asks for them.
 */

export const CATEGORIES = [
    { id: "image", label: "Images" },
    { id: "video", label: "Video" },
    { id: "audio", label: "Audio" },
    { id: "document", label: "Documents" },
    { id: "archive", label: "Archives" },
    { id: "program", label: "Programs" },
    { id: "font", label: "Fonts" },
    { id: "other", label: "Other" }
];

const EXTENSIONS = {
    image: ["jpg", "jpeg", "jpe", "jfif", "png", "apng", "gif", "webp", "avif", "bmp", "ico", "cur", "svg",
        "svgz", "tif", "tiff", "heic", "heif", "jxl", "psd", "raw", "cr2", "nef", "dng"],
    video: ["mp4", "m4v", "mkv", "webm", "mov", "avi", "wmv", "flv", "f4v", "3gp", "3g2", "mpg", "mpeg",
        "ogv", "vob", "rm", "rmvb", "asf", "mts", "m2ts"],
    audio: ["mp3", "m4a", "m4b", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "aiff", "aif", "ape",
        "alac", "mid", "midi", "amr"],
    document: ["pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "txt", "csv",
        "epub", "mobi", "azw", "azw3", "djvu", "xps", "md", "tex", "srt", "vtt", "ass", "sub"],
    archive: ["zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "tbz", "xz", "txz", "zst", "lz", "lzma", "cab",
        "iso", "img", "jar", "war"],
    program: ["exe", "msi", "msix", "appx", "dmg", "pkg", "deb", "rpm", "apk", "aab", "appimage", "run",
        "bin", "sh", "bat", "snap", "flatpak", "xpi", "crx", "ipa"],
    font: ["woff", "woff2", "ttf", "otf", "eot"]
};

const EXT_CATEGORY = new Map();
for (const [category, list] of Object.entries(EXTENSIONS)) {
    for (const ext of list) {
        EXT_CATEGORY.set(ext, category);
    }
}

const CODE_EXTENSIONS = new Set(["js", "mjs", "cjs", "css", "map", "jsx", "tsx"]);
const STREAM_EXTENSIONS = new Set(["ts", "m4s", "m3u8", "mpd", "f4m", "ism", "isml"]);
const DATA_EXTENSIONS = new Set(["json", "jsonld", "xml", "rss", "atom", "wasm"]);
const PAGE_EXTENSIONS = new Set(["html", "htm", "xhtml", "shtml", "php", "asp", "aspx", "jsp", "cgi", "pl"]);
/** Hosts whose extensionless responses are stylesheets (web font CSS). */
const STYLESHEET_HOSTS = ["fonts.googleapis.com", "use.typekit.net", "fonts.bunny.net"];

const LINK_SOURCES = new Set(["a", "a-param", "onclick"]);
/** Sources that only ever hold pictures, so an unknown extension still means an image. */
const IMAGE_SOURCES = new Set(["img", "srcset", "css-bg", "poster", "icon"]);
/** Resource Timing initiators that mean "fetched by script", i.e. usually an API call. */
const SCRIPT_INITIATORS = new Set(["fetch", "xmlhttprequest", "beacon"]);

export function extensionOf(url) {
    try {
        const path = new URL(url).pathname;
        const last = path.slice(path.lastIndexOf("/") + 1);
        const dot = last.lastIndexOf(".");
        if (dot < 1) {
            return "";
        }
        const ext = last.slice(dot + 1).toLowerCase();
        return /^[a-z0-9]{1,8}$/.test(ext) ? ext : "";
    } catch (e) {
        return "";
    }
}

/** A filename for the picker and for XDM: the page's download="" hint, else the URL's last segment. */
export function fileNameOf(url, hint) {
    if (hint && hint.trim()) {
        return hint.trim();
    }
    try {
        const u = new URL(url);
        const last = u.pathname.slice(u.pathname.lastIndexOf("/") + 1);
        if (last) {
            try {
                return decodeURIComponent(last);
            } catch (e) {
                return last;
            }
        }
        return u.hostname;
    } catch (e) {
        return url;
    }
}

function categoryFromMime(mime) {
    if (!mime) {
        return "";
    }
    mime = mime.toLowerCase();
    if (mime.startsWith("image/")) return "image";
    if (mime.startsWith("video/")) return mime.includes("mp2t") ? "stream" : "video";
    if (mime.startsWith("audio/")) return mime.includes("mpegurl") ? "stream" : "audio";
    if (mime.startsWith("font/") || mime.includes("font-")) return "font";
    if (mime.includes("javascript") || mime.includes("ecmascript") || mime === "text/css") return "code";
    if (mime.includes("mpegurl") || mime.includes("dash+xml")) return "stream";
    if (mime.includes("json") || mime.endsWith("/xml") || mime.includes("+xml")) return "data";
    if (mime === "text/html" || mime === "application/xhtml+xml") return "page";
    if (mime === "application/pdf" || mime.includes("officedocument") || mime.includes("msword")
        || mime.includes("opendocument") || mime === "text/plain" || mime === "text/csv"
        || mime.includes("epub")) return "document";
    if (mime.includes("zip") || mime.includes("x-rar") || mime.includes("x-7z") || mime.includes("x-tar")
        || mime.includes("gzip") || mime.includes("x-bzip") || mime.includes("x-xz")) return "archive";
    if (mime.includes("x-msdownload") || mime.includes("x-msi") || mime.includes("x-apple-diskimage")
        || mime.includes("vnd.android.package-archive") || mime.includes("x-debian-package")
        || mime.includes("x-rpm")) return "program";
    return "";
}

/**
 * Returns the item with `category`, `ext`, `filename`, `isLink`, `isResource` and `noise` filled
 * in, or null when it must never be offered (scripts and stylesheets).
 */
export function classify(item) {
    // A download="name.ext" hint counts when the URL itself says nothing ("/get?id=42").
    const ext = extensionOf(item.url) || (item.name ? extensionOf("http://x/" + encodeURIComponent(item.name)) : "");
    const sources = item.sources || [];
    const fromRt = sources.indexOf("rt") >= 0;
    let host = "";
    try {
        const u = new URL(item.url);
        // XDM can only fetch over the network: blob: and data: URLs exist only inside the page.
        if (u.protocol !== "http:" && u.protocol !== "https:") {
            return null;
        }
        host = u.hostname;
    } catch (e) {
        return null;
    }

    let kind = categoryFromMime(item.mime);
    if (!kind) {
        if (CODE_EXTENSIONS.has(ext)) kind = "code";
        else if (STREAM_EXTENSIONS.has(ext)) kind = "stream";
        else if (DATA_EXTENSIONS.has(ext)) kind = "data";
        else if (PAGE_EXTENSIONS.has(ext)) kind = "page";
        else if (EXT_CATEGORY.has(ext)) kind = EXT_CATEGORY.get(ext);
    }
    if (!kind && STYLESHEET_HOSTS.some(h => host === h)) {
        kind = "code";
    }
    if (!kind && fromRt && item.initiator === "script") {
        kind = "code";
    }
    if (!kind && sources.some(s => IMAGE_SOURCES.has(s))) {
        kind = "image";
    }
    if (!kind && (item.initiator === "img" || item.initiator === "image")) kind = "image";
    if (!kind && item.initiator === "video") kind = "video";
    if (!kind && item.initiator === "audio") kind = "audio";

    if (kind === "code") {
        return null;
    }

    const isLink = sources.some(s => LINK_SOURCES.has(s));
    const isResource = sources.some(s => !LINK_SOURCES.has(s));

    // Noise: things a page loads that nobody means to download in bulk.
    let noise = false;
    if (kind === "stream" || kind === "data") {
        noise = true;
    } else if (!isLink && fromRt && sources.length === 1) {
        // Seen only in Resource Timing: a script-made request with no file-like evidence is an
        // API call, and a frame document is a page, not a file.
        if (SCRIPT_INITIATORS.has(item.initiator) && !EXT_CATEGORY.has(ext) && !categoryFromMime(item.mime)) {
            noise = true;
        }
        if (item.initiator === "iframe" || item.initiator === "navigation" || kind === "page") {
            noise = true;
        }
    }

    let category = kind;
    if (!category || kind === "stream" || kind === "data" || kind === "page") {
        category = kind === "stream" ? "video" : "other";
    }

    return Object.assign({}, item, {
        ext: ext,
        filename: fileNameOf(item.url, item.name),
        category: category,
        isLink: isLink,
        isResource: isResource,
        noise: noise
    });
}

/**
 * Turns compiled filter text into a predicate over items: plain text matches anywhere in the URL,
 * name or link text; `*` / `?` make it a wildcard over the file name or URL; `/.../flags` is a
 * regular expression. Returns null when the text is not a valid pattern.
 */
export function compileFilter(text) {
    text = (text || "").trim();
    if (!text) {
        return () => true;
    }
    const re = /^\/(.+)\/([a-z]*)$/i.exec(text);
    if (re) {
        try {
            const rx = new RegExp(re[1], re[2].includes("i") ? re[2] : re[2] + "i");
            return item => rx.test(item.url) || rx.test(item.filename) || (item.text ? rx.test(item.text) : false);
        } catch (e) {
            return null;
        }
    }
    if (/[*?]/.test(text)) {
        const pattern = text.split("").map(ch => ch === "*" ? ".*" : ch === "?" ? "." :
            ch.replace(/[.+^${}()|[\]\\]/g, "\\$&")).join("");
        const rx = new RegExp("^" + pattern + "$", "i");
        return item => rx.test(item.filename) || rx.test(item.url);
    }
    const needle = text.toLowerCase();
    return item => item.url.toLowerCase().includes(needle)
        || item.filename.toLowerCase().includes(needle)
        || (item.text || "").toLowerCase().includes(needle);
}

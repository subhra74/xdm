using System;
using System.Collections.Generic;
using System.Text;

namespace XDM.Core.Util
{
    internal static class MimeTypes
    {
        static Dictionary<string, string?> mimeBuilder = new();
        public static string? Get(string key)
        {
            if (mimeBuilder.TryGetValue(key, out var ext))
            {
                return ext;
            }
            return null;
        }
        static MimeTypes()
        {
            mimeBuilder["application/x-msdownload"] = "dll";
            mimeBuilder["image/jpeg"] = "jpeg";
            mimeBuilder["image/bmp"] = "bmp";
            mimeBuilder["image/gif"] = "gif";
            mimeBuilder["image/x-icon"] = "ico";
            mimeBuilder["image/svg+xml"] = "svg";
            mimeBuilder["application/x-compressed"] = "tgz";
            mimeBuilder["application/x-shockwave-flash"] = "swf";
            mimeBuilder["video/x-msvideo"] = "avi";
            mimeBuilder["application/postscript"] = "ps";
            mimeBuilder["video/x-flv"] = "flv";
            mimeBuilder["audio/x-wav"] = "wav";
            mimeBuilder["application/vnd.ms-excel"] = "xls";
            mimeBuilder["audio/basic"] = "au";
            mimeBuilder["audio/x-aiff"] = "aiff";
            mimeBuilder["text/plain"] = "txt";
            mimeBuilder["application/x-gzip"] = "gz";
            mimeBuilder["application/msword"] = "doc";
            mimeBuilder["application/pdf"] = "pdf";
            mimeBuilder["application/x-compress"] = "z";
            mimeBuilder["application/x-javascript"] = "js";
            mimeBuilder["video/3gpp"] = "3gp";
            mimeBuilder["audio/mid"] = "mid";
            mimeBuilder["application/x-cpio"] = "cpio";
            mimeBuilder["application/vnd.ms-powerpoint"] = "ppt";
            mimeBuilder["audio/mpeg"] = "mp3";
            mimeBuilder["application/rtf"] = "rtf";
            mimeBuilder["application/x-tar"] = "tar";
            mimeBuilder["video/x-ms-wmv"] = "wmv";
            mimeBuilder["application/x-bcpio"] = "bcpio";
            mimeBuilder["text/html"] = "html";
            mimeBuilder["video/mpeg"] = "mpeg";
            mimeBuilder["image/tiff"] = "tiff";
            mimeBuilder["application/x-stuffit"] = "sit";
            mimeBuilder["application/zip"] = "zip";
            mimeBuilder["text/css"] = "css";
            mimeBuilder["application/x-gtar"] = "gtar";
            mimeBuilder["video/quicktime"] = "qt";
            mimeBuilder["video/flv"] = "flv";
            mimeBuilder["video/mp4"] = "mp4";
            mimeBuilder["video/mp2t"] = "ts";
            mimeBuilder["video/mp2t"] = "ts";
            mimeBuilder["video/x-matroska"] = "mkv";
            mimeBuilder["audio/mp4"] = "mp4";
            mimeBuilder["audio/mp2t"] = "ts";
            mimeBuilder["audio/x-matroska"] = "mkv";
            mimeBuilder["video/webm"] = "mkv";
            mimeBuilder["audio/webm"] = "mkv";
        }
    }
}

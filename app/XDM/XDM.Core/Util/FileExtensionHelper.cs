using System;
using System.Collections.Generic;
using System.Text;

namespace XDM.Core.Util
{
    public static class FileExtensionHelper
    {
        public static string GuessContainerFormatFromSegmentExtension(string ext)
        {
            ext = ext?.ToLowerInvariant() ?? string.Empty;
            if (ext == ".ts")
            {
                return ext;
            }
            foreach (var mp4Ext in new string[] { ".m4", ".mp4", ".fmp4" })
            {
                if (ext.StartsWith(mp4Ext)) return ".mp4";
            }
            return ".mkv";
        }

        public static string GuessContainerFormatFromSegmentExtension(string ext1, string ext2)
        {
            var ex1 = GuessContainerFormatFromSegmentExtension(ext1);
            var ex2 = GuessContainerFormatFromSegmentExtension(ext2);
            if (ex1 == ex2)
            {
                return ex1;
            }
            else
            {
                return ".mkv";
            }
        }

        public static string? GetExtensionFromMimeType(string? mimeType)
        {
            if (mimeType == null) return null;
            var ext = MimeTypes.Get(mimeType);
            if (!string.IsNullOrEmpty(ext))
            {
                return $".{ext}";
            }
            return null;
        }
    }
}

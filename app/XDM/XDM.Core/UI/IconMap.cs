using System;
using System.Collections.Generic;
using System.IO;

namespace XDM.Core.UI
{
    public static class IconMap
    {
        private static Dictionary<string, HashSet<string>> imageTypes = new()
        {
            ["CAT_COMPRESSED"] = new HashSet<string> { ".zip", ".gz", ".tar", ".xz", ".7z", ".rar", ".bz2" },
            ["CAT_MUSIC"] = new HashSet<string> { ".mp3", ".aac", ".ac3", ".wma", ".m4a", ".ogg", ".mka" },
            ["CAT_VIDEOS"] = new HashSet<string> { ".mp4", ".mkv", ".ts", ".webm", ".avi", ".divx", ".mov", ".m4v" },
            ["CAT_DOCUMENTS"] = new HashSet<string> { ".docx", ".doc", ".pdf", ".txt", ".xlsx", ".xls", ".html" },
            ["CAT_PROGRAMS"] = new HashSet<string> { ".exe", ".bin", ".appx", ".AppInstance.Core", ".msi", ".rpm", ".deb" }
        };

        public static string GetVectorNameForCategory(string categoryname)
        {
            return categoryname switch
            {
                "CAT_COMPRESSED" => "ri-file-zip-line",
                "CAT_MUSIC" => "ri-file-music-line",
                "CAT_VIDEOS" => "ri-movie-line",
                "CAT_DOCUMENTS" => "ri-file-text-line",
                "CAT_PROGRAMS" => "ri-microsoft-line",
                _ => "ri-file-line",
            };
        }

        private static string GetFileType(string ext)
        {
            foreach (var key in imageTypes.Keys)
            {
                var extList = imageTypes[key];
                if (extList.Contains(ext))
                {
                    return key;
                }
            }
            return "Other";
        }

        public static string GetVectorNameForFileType(string file)
        {
            var ext = Path.GetExtension(file)?.ToLowerInvariant() ?? string.Empty;
            var fileType = GetFileType(ext);
            return fileType switch
            {
                "CAT_COMPRESSED" => "ri-file-zip-fill",
                "CAT_MUSIC" => "ri-file-music-fill",
                "CAT_VIDEOS" => "ri-movie-fill",
                "CAT_DOCUMENTS" => "ri-file-text-fill",
                "CAT_PROGRAMS" => "ri-microsoft-fill",
                _ => "ri-file-fill",
            };
        }
    }
}

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using TraceLog;

namespace XDM.Core.Util
{
    public static class FileHelper
    {
        public static readonly Regex RxFileWithinQuote = new Regex("\\\"(.*)\\\"");
        public static string? SanitizeFileName(string fileName)
        {
            if (fileName == null) return fileName;
            var file = fileName.Split('/').Last();
            file = fileName.Split('\\').Last();
            return string.Join("_", file.Split(Path.GetInvalidFileNameChars()));
        }

        public static string GetDownloadFolderByFileName(string file)
        {
            try
            {
                var ext = Path.GetExtension(file)?.ToUpperInvariant();
                foreach (var category in Config.Instance.Categories)
                {
                    if (ext != null && category.FileExtensions.Contains(ext))
                    {
                        return category.DefaultFolder;
                    }
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Error");
            }
            return Config.Instance.DefaultDownloadFolder;
        }



        public static bool AddFileExtension(string name, string contentType, out string nameWithExt)
        {
            name = SanitizeFileName(name);
            if (name.EndsWith("."))
            {
                name = name.TrimEnd('.');
            }
            if (string.IsNullOrEmpty(contentType))
            {
                nameWithExt = name;
                return false;
            }
            if (contentType == "text/html")
            {
                nameWithExt = name + ".html";
                return true;
            }
            else
            {
                try
                {
                    var ext = MimeTypes.Get(contentType.ToLowerInvariant());
                    if (!string.IsNullOrEmpty(ext))
                    {
                        var prevExt = Path.GetExtension(name);
                        var nameWithoutExt = Path.GetFileNameWithoutExtension(name);
                        if (!("." + ext).Equals(prevExt, StringComparison.InvariantCultureIgnoreCase))
                        {
                            nameWithExt = nameWithoutExt + "." + ext;
                            return true;
                        }
                    }
                }
                catch (Exception ex)
                {
                    Log.Debug(ex, "Error in AddFileExtension");
                }

                nameWithExt = name;
                return true;
            }
        }

        public static string GetFileName(Uri uri, string contentType = null)
        {
            var name = Path.GetFileName(uri.LocalPath);
            if (string.IsNullOrEmpty(name))
            {
                name = uri.Host.Replace('.', '_');
            }
            name = SanitizeFileName(name);
            if (string.IsNullOrEmpty(contentType))
            {
                return name;
            }

            if (contentType == "text/html")
            {
                return Path.ChangeExtension(name, ".html");
            }
            else
            {
                if (!Path.HasExtension(name))
                {
                    var ext = MimeTypes.Get(contentType.ToLowerInvariant());
                    if (!string.IsNullOrEmpty(ext))
                    {
                        name += "." + ext;
                    }
                }
                return name;
            }
        }

        public static string GetUniqueFileName(string file, string folder)
        {
            var path = Path.Combine(folder, file);
            var name = Path.GetFileNameWithoutExtension(file);
            var ext = Path.GetExtension(file);
            var count = 0;
            while (File.Exists(path))
            {
                count++;
                path = Path.Combine(folder, name + "_" + count + ext);
            }
            return count == 0 ? file : name + "_" + count + ext;
        }

        public static string GetFileNameFromQuote(string text)
        {
            if (string.IsNullOrEmpty(text))
            {
                return null;
            }
            var matcher = RxFileWithinQuote.Match(text);
            if (matcher.Success)
            {
                return matcher.Groups[1].Value;
            }
            return null;
        }

        public static string QuoteFilePathIfNeeded(string file)
        {
            if (file.Contains(" "))
            {
                return Environment.OSVersion.Platform == PlatformID.Win32NT ? $"\"{file}\"" : $"\"{file}\"";
            }
            return file;
        }
    }
}

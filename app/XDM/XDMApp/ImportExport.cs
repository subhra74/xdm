using System;
using System.Collections.Generic;
using System.IO;
#if NET35
using Ionic.Zip;
#else
using System.IO.Compression;
#endif
using System.Linq;
using XDM.Core.Lib.Common;
using System.Globalization;
using XDM.Core.Lib.Downloader;

namespace XDMApp
{
    internal class ImportExport
    {
        internal static void Import(string path)
        {
            var tempDir = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString());
            Directory.CreateDirectory(tempDir);

#if NET35
            ZipFile.Read(path).ExtractAll(tempDir);
#else
            using FileStream zipToOpen = new(path, FileMode.Open);
            using ZipArchive archive = new(zipToOpen, ZipArchiveMode.Read);
            archive.ExtractToDirectory(tempDir);
#endif

            var existingDownloads = new HashSet<string>();

            var finishedDownloads = TransactedIO.ReadFinishedList("finished-downloads.dat", Config.DataDir);
            // JsonConvert.DeserializeObject<List<FinishedDownloadEntry>>(
            //File.ReadAllText(Path.Combine(
            //    Config.DataDir,
            //    "finished-downloads.json"))) ?? new List<FinishedDownloadEntry>(0);

            foreach (var d in finishedDownloads)
            {
                existingDownloads.Add(d.Id);
            }

            var incompleteDownloads = TransactedIO.ReadInProgressList("incomplete-downloads.dat", Config.DataDir);// JsonConvert.DeserializeObject<List<InProgressDownloadEntry>>(
            //File.ReadAllText(Path.Combine(
            //                    Config.DataDir,
            //                    "incomplete-downloads.json"))) ?? new List<InProgressDownloadEntry>(0);

            foreach (var d in incompleteDownloads)
            {
                existingDownloads.Add(d.Id);
            }

            var importedFinishedDownloads = new List<FinishedDownloadEntry>(
                TransactedIO.ReadFinishedList("finished-downloads.dat", Config.DataDir));
            //JsonConvert.DeserializeObject<List<FinishedDownloadEntry>>(
            //                File.ReadAllText(Path.Combine(
            //                    tempDir,
            //                    "finished-downloads.dat"))) ?? new List<FinishedDownloadEntry>(0);

            var importedUnfinishedDownloads = new List<InProgressDownloadEntry>(
                TransactedIO.ReadInProgressList("incomplete-downloads.dat", Config.DataDir));
            //JsonConvert.DeserializeObject<List<InProgressDownloadEntry>>(
            //                File.ReadAllText(Path.Combine(
            //                    tempDir,
            //                    "incomplete-downloads.dat"))) ?? new List<InProgressDownloadEntry>(0);

            foreach (var d1 in importedFinishedDownloads)
            {
                if (existingDownloads.Contains(d1.Id)) continue;
                finishedDownloads.Add(d1);
            }

            foreach (var d1 in incompleteDownloads)
            {
                if (existingDownloads.Contains(d1.Id)) continue;
                incompleteDownloads.Add(d1);
            }

            importedFinishedDownloads.Sort((x, y) => y.DateAdded.CompareTo(x.DateAdded));
            importedUnfinishedDownloads.Sort((x, y) => y.DateAdded.CompareTo(x.DateAdded));

            TransactedIO.WriteFinishedList(finishedDownloads, "finished-downloads.dat", Config.DataDir);
            TransactedIO.WriteInProgressList(incompleteDownloads, "incomplete-downloads.dat", Config.DataDir);

            //File.WriteAllText(Path.Combine(Config.DataDir, "incomplete-downloads.json"),
            //            JsonConvert.SerializeObject(incompleteDownloads));

            //File.WriteAllText(Path.Combine(Config.DataDir, "finished-downloads.json"),
            //    JsonConvert.SerializeObject(finishedDownloads));

            foreach (var file in Directory.GetFiles(tempDir, "*.state"))
            {
                File.Copy(file, Path.Combine(Config.DataDir, Path.GetFileName(file)));
            }

            foreach (var file in Directory.GetFiles(tempDir, "*.info"))
            {
                File.Copy(file, Path.Combine(Config.DataDir, Path.GetFileName(file)));
            }

        }

        internal static void Export(string path)
        {
            var dir = new DirectoryInfo(Config.DataDir);
            var filesToAdd = new List<string>();
            filesToAdd.AddRange(dir.GetFiles("*downloads.dat").Select(x => x.FullName));
            filesToAdd.AddRange(dir.GetFiles("*.state").Select(x => x.FullName));
            filesToAdd.AddRange(dir.GetFiles("*.info").Select(x => x.FullName));

#if NET35
            using var zip = new ZipFile(path);
            foreach (var file in filesToAdd)
            {
                zip.AddFile(file);
            }
            zip.Save();
#else
            using FileStream zipToCreate = new(path, FileMode.Create);
            using ZipArchive archive = new(zipToCreate, ZipArchiveMode.Create);
            foreach (var file in filesToAdd)
            {
                archive.CreateEntryFromFile(file, Path.GetFileName(file));
            }
#endif
        }

        //internal static void ImportFromPreviousVersion()
        //{
        //    var file = Path.Combine(Environment.GetEnvironmentVariable("%USERPROFILE%"), @".xdman\downloads.txt");
        //    if (File.Exists(file))
        //    {
        //        var lines = File.ReadAllLines(file);
        //        var c = 0;
        //        var count = Int32.Parse(lines[c++]);
        //        for (int i = 0; i < count; i++)
        //        {
        //            var fieldCount = Int32.Parse(lines[c++]);

        //            string id = null, name = null, folder = null;
        //            bool finished = false;
        //            int progress = 0;
        //            long downloaded = 0, size = 0;
        //            DateTime date = DateTime.Now;

        //            string url = null, url2 = null, type = null;
        //            long len, len2, bitrate;
        //            Dictionary<string, List<string>> headers = new(), headers2 = new();
        //            List<string> cookies = new(), cookies2 = new();
        //            for (int j = 0; j < fieldCount; j++)
        //            {
        //                var str = lines[c++];
        //                var index = str.IndexOf(':');
        //                if (index < 0)
        //                {
        //                    continue;
        //                }
        //                var key = str.Substring(0, index).Trim();
        //                var val = str.Substring(index + 1).Trim();
        //                switch (key)
        //                {
        //                    case "id":
        //                        id = val;
        //                        break;
        //                    case "file":
        //                        name = val;
        //                        break;
        //                    case "folder":
        //                        folder = val;
        //                        break;
        //                    case "state":
        //                        finished = Int32.Parse(val) == 100;
        //                        break;
        //                    case "progress":
        //                        progress = Int32.Parse(val);
        //                        break;
        //                    case "downloaded":
        //                        downloaded = Int64.Parse(val);
        //                        break;
        //                    case "size":
        //                        size = Int64.Parse(val);
        //                        break;
        //                    case "date":
        //                        date = DateTime.ParseExact(val, "yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture);
        //                        break;
        //                    default:
        //                        break;
        //                }
        //            }
        //            if (string.IsNullOrEmpty(id))
        //            {
        //                continue;
        //            }
        //            var metadataFile = Path.Combine(Environment.GetEnvironmentVariable("%USERPROFILE%"),
        //                $@".xdman\\metadata\{id}");
        //            if (File.Exists(metadataFile))
        //            {
        //                var fp = new StreamReader(metadataFile);
        //                var downloadType = Int32.Parse(fp.ReadLine());
        //                switch (downloadType)
        //                {
        //                    case 1000:
        //                        type = DownloadTypes.Http;
        //                        break;
        //                    case 1001:
        //                        type = DownloadTypes.Hls;
        //                        break;
        //                    case 1003:
        //                        type = DownloadTypes.Dash;
        //                        break;
        //                    default:
        //                        type = null;
        //                        break;
        //                }
        //                while (true)
        //                {
        //                    var str = fp.ReadLine();
        //                    if (string.IsNullOrEmpty(str)) break;
        //                    var index = str.IndexOf(':');
        //                    if (index < 0)
        //                    {
        //                        continue;
        //                    }
        //                    var key = str.Substring(0, index).Trim();
        //                    var val = str.Substring(index + 1).Trim();
        //                    switch (key)
        //                    {
        //                        case "url":
        //                            url = val;
        //                            break;
        //                        case "url2":
        //                            url2 = val;
        //                            break;
        //                        case "size":
        //                        case "len1":
        //                            len = Int64.Parse(val);
        //                            break;
        //                        case "len2":
        //                            len2 = Int64.Parse(val);
        //                            break;
        //                        case "bitrate":
        //                            bitrate = Int32.Parse(val);
        //                            break;
        //                        case "header":
        //                            ParseHeaderAndCookie(val, headers, cookies);
        //                            break;
        //                        case "header2":
        //                            ParseHeaderAndCookie(val, headers2, cookies2);
        //                            break;
        //                        default:
        //                            break;
        //                    }
        //                }
        //            }
        //            if (!string.IsNullOrEmpty(type))
        //            {
        //                if (finished)
        //                {
        //                    var finishedEntry = new FinishedDownloadEntry
        //                    {
        //                        Id = id,
        //                        DateAdded = date,
        //                        DownloadType=type,
                                
        //                    };
        //                }
        //            }
        //        }
        //    }
        //}

        //private static void ParseHeaderAndCookie(string val, Dictionary<string, List<string>> headers, List<string> cookies)
        //{
        //    {
        //        var index1 = val.IndexOf(':');
        //        if (index1 < 0)
        //        {
        //            return;
        //        }
        //        var key1 = val.Substring(0, index1).Trim();
        //        var val1 = val.Substring(index1 + 1).Trim();
        //        if (key1.Equals("cookie", StringComparison.InvariantCultureIgnoreCase))
        //        {
        //            cookies.Add(val1);
        //        }
        //        else
        //        {
        //            if (!headers.TryGetValue(key1, out List<string> headerList))
        //            {
        //                headerList.Add(val1);
        //            }
        //            else
        //            {
        //                headers[key1] = new List<string> { val1 };
        //            }
        //        }
        //    }
        //}
    }
}

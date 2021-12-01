using Newtonsoft.Json;
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

            var finishedDownloads = JsonConvert.DeserializeObject<List<FinishedDownloadEntry>>(
                            File.ReadAllText(Path.Combine(
                                Config.DataDir,
                                "finished-downloads.json"))) ?? new List<FinishedDownloadEntry>(0);

            foreach (var d in finishedDownloads)
            {
                existingDownloads.Add(d.Id);
            }

            var incompleteDownloads = JsonConvert.DeserializeObject<List<InProgressDownloadEntry>>(
                            File.ReadAllText(Path.Combine(
                                Config.DataDir,
                                "incomplete-downloads.json"))) ?? new List<InProgressDownloadEntry>(0);

            foreach (var d in incompleteDownloads)
            {
                existingDownloads.Add(d.Id);
            }

            var importedFinishedDownloads = JsonConvert.DeserializeObject<List<FinishedDownloadEntry>>(
                            File.ReadAllText(Path.Combine(
                                tempDir,
                                "finished-downloads.json"))) ?? new List<FinishedDownloadEntry>(0);

            var importedUnfinishedDownloads = JsonConvert.DeserializeObject<List<InProgressDownloadEntry>>(
                            File.ReadAllText(Path.Combine(
                                tempDir,
                                "incomplete-downloads.json"))) ?? new List<InProgressDownloadEntry>(0);

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

            File.WriteAllText(Path.Combine(Config.DataDir, "incomplete-downloads.json"),
                        JsonConvert.SerializeObject(incompleteDownloads));

            File.WriteAllText(Path.Combine(Config.DataDir, "finished-downloads.json"),
                JsonConvert.SerializeObject(finishedDownloads));

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
            filesToAdd.AddRange(dir.GetFiles("*downloads.json").Select(x => x.FullName));
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
    }
}

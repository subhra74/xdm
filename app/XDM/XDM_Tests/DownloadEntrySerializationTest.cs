using System;
using System.Collections.Generic;
using System.IO;
using Newtonsoft.Json;
using NUnit.Framework;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Downloader;

namespace XDM.SystemTests
{
    public class DownloadEntrySerializationTest
    {
        [Test]
        public void TestSerializeDeserializeFinishedDownloadEntryOk()
        {
            var file = Guid.NewGuid().ToString();
            var folder = Path.GetTempPath();
            var list = new List<FinishedDownloadEntry>();
            list.Add(new FinishedDownloadEntry
            {
                Id = Guid.NewGuid().ToString(),
                Name = "Sample entry 1",
                DateAdded = DateTime.Now,
                DownloadType = "Http",
                FileNameFetchMode = FileNameFetchMode.FileNameAndExtension,
                Size = 12345
            });
            list.Add(new FinishedDownloadEntry
            {
                Id = Guid.NewGuid().ToString(),
                Name = "Sample entry 2",
                DateAdded = DateTime.Now,
                DownloadType = "Http",
                FileNameFetchMode = FileNameFetchMode.FileNameAndExtension,
                Size = 1234567
            });
            TransactedIO.WriteFinishedList(list, file, folder);
            Console.WriteLine(JsonConvert.SerializeObject(TransactedIO.ReadFinishedList(file, folder), Formatting.Indented));
        }

        [Test]
        public void TestSerializeDeserializeInProgressDownloadEntryOk()
        {
            var file = Guid.NewGuid().ToString();
            var folder = Path.GetTempPath();
            var list = new List<InProgressDownloadEntry>();
            list.Add(new InProgressDownloadEntry
            {
                Id = Guid.NewGuid().ToString(),
                Name = "Sample entry 1",
                DateAdded = DateTime.Now,
                DownloadType = "Http",
                FileNameFetchMode = FileNameFetchMode.FileNameAndExtension,
                Size = 12345,
                Progress=10,
                TargetDir="abc"
            });
            list.Add(new InProgressDownloadEntry
            {
                Id = Guid.NewGuid().ToString(),
                Name = "Sample entry 2",
                DateAdded = DateTime.Now,
                DownloadType = "Http",
                FileNameFetchMode = FileNameFetchMode.FileNameAndExtension,
                Size = 1234567,
                Progress = 20,
                TargetDir = "abcd"
            });
            TransactedIO.WriteInProgressList(list, file, folder);
            Console.WriteLine(JsonConvert.SerializeObject(TransactedIO.ReadInProgressList(file, folder), Formatting.Indented));
        }
    }
}

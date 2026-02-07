using Newtonsoft.Json;
using NUnit.Framework;
using System;
using System.Collections.Generic;
using System.IO;
using XDM.Core.Lib.Common;

namespace XDM.SystemTests
{
    public class ConfigTest
    {
        [Test]
        public void TestSerializeDeserializeOk()
        {
            Config.DataDir = Path.GetTempPath();
            Config.LoadConfig(); //initialize config
            Config.Instance.RecentFolders.Add("abc");
            var list = new List<Category>(Config.Instance.Categories);
            list.Add(new Category
            {
                DefaultFolder = "abc",
                DisplayName = "xyz",
                FileExtensions = new HashSet<string>(new string[] { ".a", ".b", ".c" }),
                Name = "mmm",
                IsPredefined = false
            });
            Config.Instance.Categories = list;
            Config.Instance.BlockedHosts = new string[] { "abc" };
            Config.Instance.MaxRetry = 20;
            Config.Instance.MaxParallelDownloads = 20;
            Config.SaveConfig();
            Config.LoadConfig();
            Console.WriteLine(JsonConvert.SerializeObject(Config.Instance, Formatting.Indented));
        }
    }
}

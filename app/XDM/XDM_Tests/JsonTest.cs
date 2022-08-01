using MediaParser.YouTube;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using NUnit.Framework;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using YDLWrapper;

namespace XDM.SystemTests
{

    class JsonTest
    {
        [Test]
        public void ProcessJson()
        {
            var res1 = YDLOutputParser.Parse(@"C:\Users\subhrad\Desktop\80a44682-5ea8-4193-bc52-34ee568ce9bb.json");
            Console.WriteLine(JsonConvert.SerializeObject(res1));

            //var res2 = YDLOutputParser.Parse(@"C:\Users\subhro\Desktop\ccc39b43-0fd1-464d-ba11-d608242dcdc2.json");
            //Console.WriteLine(JsonConvert.SerializeObject(res2));
        }

        [Test]
        public void ProcessYtJson()
        {
            var item = YoutubeDataFormatParser.GetFormats(@"C:\Users\subhrad\Desktop\159_.json");
            Console.WriteLine(item.DualVideoItems.Count + " " + item.VideoItems.Count);
            foreach(var a in item.DualVideoItems)
            {
                Console.WriteLine(a.FormatDescription);
            }
        }
    }
}

using NUnit.Framework;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using XDM.Core.Lib.Common;
using XDM.Core.Lib.Common.Collections;

namespace XDM.SystemTests
{
    class GenericTests
    {
        [Test]
        public void TestGenericOrderDictionary()
        {
            var dict = new GenericOrderedDictionary<string, bool>();
            dict.Add("Hello", true);
            dict.Add("Heeelllllo", true);
            dict.Add("World", false);
            foreach (var key in dict.Keys)
            {
                Console.WriteLine(key + " " + dict[key]);
            }
            Console.WriteLine("First: "+dict.First());
            Console.WriteLine("Last: " + dict.Last());
        }
    }
}

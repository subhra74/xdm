using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

using XDM.Core.Lib.Common;

namespace XDM.Core.Lib.Downloader.Adaptive
{
    public class SimpleStreamMap : IChunkStreamMap
    {
        public Dictionary<string, string> StreamMap { get; set; }
        public string GetStream(string prefix)
        {
            return StreamMap[prefix];
        }
    }
}

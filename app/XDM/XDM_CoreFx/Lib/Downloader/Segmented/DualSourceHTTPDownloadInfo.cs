using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Text;


namespace XDM.Core.Lib.Common.Segmented
{
    public class DualSourceHTTPDownloadInfo
    {
        public string Uri1 { get; set; }
        public string Uri2 { get; set; }
        public Dictionary<string, string> Cookies1 { get; set; }
        public Dictionary<string, string> Cookies2 { get; set; }
        public Dictionary<string, List<string>> Headers1 { get; set; }
        public Dictionary<string, List<string>> Headers2 { get; set; }
        public string File { get; set; }
        public long ContentLength { get; set; }
    }
}

using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Text;


namespace XDM.Core.Lib.Common.Segmented
{
    public class SingleSourceHTTPDownloadInfo
    {
        public string Uri { get; set; }
        public Dictionary<string, string> Cookies { get; set; }
        public Dictionary<string, List<string>> Headers { get; set; }
        public string File { get; set; }
        public long ContentLength { get; set; }
    }
}

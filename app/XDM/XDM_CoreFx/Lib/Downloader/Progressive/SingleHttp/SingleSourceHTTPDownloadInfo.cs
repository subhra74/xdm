using System.Collections.Generic;


namespace XDM.Core.Lib.Downloader.Progressive.SingleHttp
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

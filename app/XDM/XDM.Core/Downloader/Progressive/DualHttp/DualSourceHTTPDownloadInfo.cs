using System.Collections.Generic;


namespace XDM.Core.Downloader.Progressive.DualHttp
{
    public class DualSourceHTTPDownloadInfo : IRequestData
    {
        public string Uri1 { get; set; }
        public string Uri2 { get; set; }
        public Dictionary<string, string> Cookies1 { get; set; }
        public Dictionary<string, string> Cookies2 { get; set; }
        public Dictionary<string, List<string>> Headers1 { get; set; }
        public Dictionary<string, List<string>> Headers2 { get; set; }
        public string File { get; set; }
        public long ContentLength { get; set; }
        public long ContentLength1 { get; set; }
        public long ContentLength2 { get; set; }
        public string ContentType1 { get; set; }
        public string ContentType2 { get; set; }
    }
}

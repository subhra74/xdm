using Newtonsoft.Json;
using System.Collections.Generic;

namespace XDM.App.Host
{
    public class DownloadMessage
    {
        public string? Url { get; set; }
        public string? Cookie { get; set; }
        public string[]? Headers { get; set; }
        public string? FileName { get; set; }
        public long? FileSize { get; set; }
        public string? MimeType { get; set; }
        [JsonProperty(PropertyName = "download_headers")]
        public RequestData? RequestData { get; set; }
        [JsonProperty(PropertyName = "tab_update")]
        public TabInfo? TabUpdate { get; set; }
        public string? Vid { get; set; }
        public bool? Clear { get; set; }
    }

    public class RequestData
    {
        public string Url { get; set; }
        public string File { get; set; }
        public string Method { get; set; }
        public string UserAgent { get; set; }
        public string TabUrl { get; set; }
        public string TabId { get; set; }
        public Dictionary<string, List<string>> RequestHeaders { get; set; }
        public Dictionary<string, List<string>> ResponseHeaders { get; set; }
    }

    public class TabInfo
    {
        public string Url { get; set; }
        public string Title { get; set; }
    }
}
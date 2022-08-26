using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace XDM.App.Host
{
    public class DownloadMessage
    {
        public string? Url { get; set; }
        public string? Cookie { get; set; }
        public string[]? Headers { get; set; }
        public string? FileName { get; set; }
        public long FileSize { get; set; }
        public string? MimeType { get; set; }
    }
}

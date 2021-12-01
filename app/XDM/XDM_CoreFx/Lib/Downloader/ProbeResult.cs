using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace XDM.Core.Lib.Common
{
    public class ProbeResult
    {
        public bool Resumable { get; set; }
        public long? ResourceSize { get; set; }
        public Uri? FinalUri { get; set; }
        public string? AttachmentName { get; set; }
        public string? ContentType { get; set; }
        public DateTime LastModified { get; set; }
    }
}

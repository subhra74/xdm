using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace XDM.Core.MediaParser.Hls
{
    public class HlsMediaSegment
    {
        public HlsMediaSegment(Uri url)
        {
            this.Url = url;
        }
        public Uri Url { get; set; }
        public KeyValuePair<long, long> ByteRange { get; set; }
        public double Duration { get; set; }
        public Uri? KeyUrl { get; set; }
        public string? IV { get; set; }
    }
}

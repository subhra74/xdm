using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace MediaParser.Hls
{
    public class HlsPlaylist
    {
        public IList<HlsMediaSegment>? MediaSegments { get; set; }
        public bool IsEncrypted { get; set; }
        public double TotalDuration { get; set; }
        public bool HasByteRange { get; set; }
        public bool IsKeyIFrameOnly { get; set; }
    }
}

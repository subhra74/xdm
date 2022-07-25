using System;
using System.Collections.Generic;

namespace XDM.Core.Downloader.Adaptive.Dash
{
    public class MultiSourceDASHDownloadInfo : MultiSourceDownloadInfo
    {
        public List<Uri> AudioSegments { get; set; }
        public List<Uri> VideoSegments { get; set; }
        public long Duration { get; set; }
        public string Url { get; set; }
        public string VideoMimeType { get; set; }
        public string AudioMimeType { get; set; }
        public string VideoFormat { get; set; }
        public string AudioFormat { get; set; }
        //public int Width { get; }
        //public int Height { get; }
        //public string VideoCodec { get; }
        //public string AudioCodec { get; }
        //public long VideoBandwidth { get; }
        //public long VideoDuration { get; }
        //public long AudioBandwidth { get; }
        //public long AudioDuration { get; }
        //public string Language { get; }
    }
}

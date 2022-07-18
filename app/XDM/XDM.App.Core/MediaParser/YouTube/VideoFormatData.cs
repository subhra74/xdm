using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace MediaParser.YouTube
{
    public class VideoFormatData
    {
        public StreamingData? StreamingData { get; set; }
        public VideoDetails? VideoDetails { get; set; }
    }

    public class VideoFormat
    {
        public string? SignatureCipher { get; set; }
        public string? MimeType { get; set; }
        public int Width { get; set; }
        public int Height { get; set; }
        public string? QualityLabel { get; set; }
        public int Itag { get; set; }
        public long Bitrate { get; set; }
        public long ContentLength { get; set; }
        public string? Url { get; set; }
    }

    public class VideoDetails
    {
        public string? Title { get; set; }
    }

    public class StreamingData
    {
        public List<VideoFormat>? Formats { get; set; }
        public List<VideoFormat>? AdaptiveFormats { get; set; }
    }

    public class ParsedDualUrlVideoFormat
    {
        public ParsedDualUrlVideoFormat(string title, string videoUrl, string audioUrl,
            string formatDescription, string mediaContainer, long size)
        {
            this.Title = title;
            this.VideoUrl = videoUrl;
            this.AudioUrl = audioUrl;
            this.FormatDescription = formatDescription;
            this.MediaContainer = mediaContainer;
            this.Size = size;
        }

        public string Title { get; }
        public string VideoUrl { get; }
        public string AudioUrl { get; }
        public string FormatDescription { get; }
        public string MediaContainer { get; }
        public long Size { get; }
    }

    public class ParsedUrlVideoFormat
    {
        public ParsedUrlVideoFormat(string title, string mediaUrl, string formatDescription, string mediaContainer, long size)
        {
            this.Title = title;
            this.MediaUrl = mediaUrl;
            this.FormatDescription = formatDescription;
            this.MediaContainer = mediaContainer;
            this.Size = size;
        }

        public string Title { get; }
        public string MediaUrl { get; }
        public string FormatDescription { get; }
        public string MediaContainer { get; }
        public long Size { get; }
    }
}

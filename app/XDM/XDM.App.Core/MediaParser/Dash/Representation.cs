using System;
using System.Collections.Generic;

namespace MediaParser.Dash
{
    public class Representation
    {
        public int Width { get; }
        public int Height { get; }
        public string Codec { get; }
        public long Bandwidth { get; }
        public long Duration { get; }
        public List<Uri> Segments { get; }
        public string MimeType { get; }
        public string Language { get; }

        public Representation(List<Uri> segments, int width, int height,
            string codec, long bandwidth, long duration, string mimeType, string language)
        {
            this.Segments = segments;
            this.Width = width;
            this.Height = height;
            this.Codec = codec;
            this.Bandwidth = bandwidth;
            this.Duration = duration;
            this.MimeType = mimeType;
            this.Language = language;
        }
    }
}

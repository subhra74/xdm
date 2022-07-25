using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

#if !NET5_0_OR_GREATER
using XDM.Compatibility;
#endif

namespace XDM.Core.MediaParser.Hls
{
    public class HlsPlaylistContainer
    {
        public Uri? VideoPlaylist { get; set; }
        public Uri? AudioPlaylist { get; set; }
        public Dictionary<string, string>? Attributes { get; set; }
        public string? Quality => GetQuality();
        private string? GetQuality()
        {
            if (Attributes == null) return null;
            var resolution = Attributes.GetValueOrDefault("RESOLUTION");
            var bandwidth = Attributes.GetValueOrDefault("BANDWIDTH");
            var name = Attributes.GetValueOrDefault("NAME");
            var lang = Attributes.GetValueOrDefault("LANGUAGE");
            var text = new StringBuilder();
            if (resolution != null)
            {
                text.Append(resolution);
            }
            if (bandwidth != null)
            {
                try
                {
                    if (text.Length > 0) text.Append(' ');
                    text.Append((Int64.Parse(bandwidth) / 1024) + " Kbps");
                }
                catch { }
            }
            if (name != null)
            {
                if (text.Length > 0) text.Append(' ');
                text.Append(name);
            }
            if (name == null && lang != null)
            {
                if (text.Length > 0) text.Append(' ');
                text.Append(lang);
            }
            return text.Length > 0 ? text.ToString() : null;
        }
    }

    public enum MediaType
    {
        /// <summary>
        /// Contains both audio and video
        /// </summary>
        GENERIC,
        /// <summary>
        /// Contains only video
        /// </summary>
        VIDEO_ONLY,
        /// <summary>
        /// Contains only audio
        /// </summary>
        AUDIO_ONLY
    }
}

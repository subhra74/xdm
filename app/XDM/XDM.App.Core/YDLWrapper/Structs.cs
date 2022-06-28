using System.Collections.Generic;
using System.Text;

namespace YDLWrapper
{
    public enum YDLEntryType
    {
        Http, Dash, MpegDash, Hls
    }

    public struct YDLVideoEntry
    {
        public string Title { get; set; }
        public List<YDLVideoFormatEntry> Formats { get; set; }

        public override string ToString()
        {
            return Title;
        }
    }

    public struct YDLVideoFormatEntry
    {
        public YDLEntryType YDLEntryType { get; set; }
        public string AudioUrl { get; set; }
        public string VideoUrl { get; set; }
        public IList<Fragment> AudioFragments { get; set; }
        public IList<Fragment> VideoFragments { get; set; }
        public string AudioFormat { get; set; }
        public string VideoFormat { get; set; }
        public string FileExt { get; set; }
        public string Title { get; set; }
        public string AudioCodec { get; set; }
        public string VideoCodec { get; set; }
        public string Abr { get; set; }
        public string Width { get; set; }
        public string Height { get; set; }
        public string FragmentBaseUrl { get; set; }

        public override string ToString()
        {
            var textBuf = new StringBuilder();
            if (this.Height != null)
            {
                textBuf.Append(this.Height + "p ");
            }
            if (textBuf.Length == 0 && this.VideoFormat != null)
            {
                textBuf.Append(this.VideoFormat);
            }
            if (this.Abr != null)
            {
                textBuf.Append(this.Abr + " kbps ");
            }
            if (this.FileExt != null)
            {
                textBuf.Append(" [" + this.FileExt.ToUpperInvariant() + "]");
            }
            return textBuf.ToString();
        }
    }

    public readonly struct YDLOutput
    {
        public YDLPlaylist? Playlist { get; }
        public YDLFormatList? FormatList { get; }

        public YDLOutput(YDLPlaylist? Playlist, YDLFormatList? FormatList)
        {
            this.Playlist = Playlist;
            this.FormatList = FormatList;
        }
    }

    public struct YDLPlaylist
    {
        public YDLFormatList[] Entries { get; set; }
    }

    public struct YDLFormatList
    {
        public string Title { get; set; }
        public YDLFormat[] Formats { get; set; }
    }

    public struct YDLFormat
    {
        public string Title { get; set; }
        public string Width { get; set; }
        public string Url { get; set; }
        public double Vbr { get; set; }
        public string Container { get; set; }
        public string Acodec { get; set; }
        public string Format { get; set; }
        public string Format_Note { get; set; }
        public string Vcodec { get; set; }
        public string Ext { get; set; }
        public string Protocol { get; set; }
        public string Height { get; set; }
        public string Filesize { get; set; }
        public string Abr { get; set; }
        public string Fragment_Base_Url { get; set; }
        public IList<Fragment> Fragments { get; set; }
        //public Dictionary<string, string> Http_Headers { get; set; }
        public string Manifest_Url { get; set; }
    }

    public struct Fragment
    {
        public string Path { get; set; }
        public string Duration { get; set; }
    }
}

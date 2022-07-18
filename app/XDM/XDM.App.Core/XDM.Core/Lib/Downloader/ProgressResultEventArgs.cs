using System;

namespace XDM.Core.Lib.Downloader
{
    public class ProgressResultEventArgs : EventArgs
    {
        public int Progress { get; set; }
        public double DownloadSpeed { get; set; }
        public long Eta { get; set; }
        public long Downloaded { get; set; }
    }
}

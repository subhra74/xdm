using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

using XDM.Core;

namespace XDM.Core.UI
{
    public interface IInProgressDownloadRow
    {
        public string FileIconText { get; }

        public string Name { get; set; }

        public long Size { get; set; }

        public DateTime DateAdded { get; set; }

        public int Progress { get; set; }

        public DownloadStatus Status { get; set; }

        public string DownloadSpeed { get; set; }

        public string ETA { get; set; }

        public InProgressDownloadEntry DownloadEntry { get; }
    }
}

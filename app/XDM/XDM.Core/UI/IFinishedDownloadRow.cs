using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

using XDM.Core;

namespace XDM.Core.UI
{
    public interface IFinishedDownloadRow
    {
        public string FileIconText { get; }

        public string Name { get; }

        public long Size { get; }

        public DateTime DateAdded { get; }

        public FinishedDownloadItem DownloadEntry { get; }
    }
}

using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

using MediaParser.Hls;
using XDM.Core.Lib.Common.Segmented;

namespace XDM.Core.Lib.Common.Hls
{
    public class MultiSourceHLSDownloadInfo : MultiSourceDownloadInfo
    {
        public string VideoUri { get; set; }
        public string AudioUri { get; set; }
    }
}

using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;


namespace XDM.Core
{
    public struct StreamingVideoDisplayInfo
    {
        public string Quality { get; set; }
        public long Size { get; set; }
        public long Duration { get; set; }
        public DateTime CreationTime { get; set; }
    }
}

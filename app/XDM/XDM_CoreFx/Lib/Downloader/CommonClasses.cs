using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

using XDM.Core.Lib.Common;

namespace XDM.Core.Lib.Common
{
    public class SimpleStreamMap : IChunkStreamMap
    {
        public Dictionary<string, string> StreamMap { get; set; }
        public string GetStream(string prefix)
        {
            return StreamMap[prefix];
        }
    }

    public class ProgressResultEventArgs : EventArgs
    {
        public int Progress { get; set; }
        public double DownloadSpeed { get; set; }
        public long Eta { get; set; }
        public long Downloaded { get; set; }
    }

    public class DownloadFailedEventArgs : EventArgs
    {
        public DownloadFailedEventArgs(ErrorCode errorCode)
        {
            ErrorCode = errorCode;
        }
        public ErrorCode ErrorCode { get; }
    }

    public enum FileNameFetchMode
    {
        None, //use given name only
        FileNameAndExtension, //
        ExtensionOnly
    }
}

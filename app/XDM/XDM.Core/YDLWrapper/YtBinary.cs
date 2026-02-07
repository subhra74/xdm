using System;
using System.Collections.Generic;
using System.Text;

namespace YDLWrapper
{
    public struct YtBinary
    {
        public YtBinaryType BinaryType { get; set; }
        public string Path { get; set; }
    }

    public enum YtBinaryType
    {
        YtDlp, Yt
    }
}

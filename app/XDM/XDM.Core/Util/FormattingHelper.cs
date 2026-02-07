using System;
using System.Collections.Generic;
using System.Text;

namespace XDM.Core.Util
{
    public static class FormattingHelper
    {
        private const int GB = 1024 * 1024 * 1024, MB = 1024 * 1024, KB = 1024;

        public static string ToHMS(long sec)
        {
            long hrs = 0, min = 0;
            hrs = sec / 3600;
            min = (sec % 3600) / 60;
            sec = sec % 60;
            var str = hrs.ToString().PadLeft(2, '0') + ":" + min.ToString().PadLeft(2, '0') + ":" + sec.ToString().PadLeft(2, '0');
            return str;
        }

        public static string FormatSize(double length)
        {
            if (length <= 0)
                return "---";
            if (length > GB)
            {
                return $"{length / GB:F1}G";
            }
            else if (length > MB)
            {
                return $"{length / MB:F1}M";
            }
            else if (length > KB)
            {
                return $"{length / KB:F1}K";
            }
            else
            {
                return $"{(long)length}B";
            }
        }
    }
}

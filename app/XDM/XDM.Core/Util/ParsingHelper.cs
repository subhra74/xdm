using System;
using System.Collections.Generic;
using System.Text;
using System.Text.RegularExpressions;

namespace XDM.Core.Util
{
    public static class ParsingHelper
    {
        public static readonly Regex RxDuration = new Regex(@"Duration:\s+(\d\d):(\d\d):(\d\d)\.\d\d,\s", RegexOptions.Compiled);
        public static readonly Regex RxTime = new Regex(@"frame=.*?time=(\d\d):(\d\d):(\d\d)\.\d\d.*?bitrate=", RegexOptions.Compiled);

        public static KeyValuePair<string, string> ParseKeyValuePair(string line, char delimiter)
        {
            line = line.Trim();
            int index = line.IndexOf(delimiter);
            if (index < 1) return new KeyValuePair<string, string>("", "");
            string key = line.Substring(0, index).Trim();
            string val = line.Substring(index + 1).Trim();
            return new KeyValuePair<string, string>(key: key, value: val);
        }

        public static bool ParseKeyValuePair(string line, char delimiter, out KeyValuePair<string, string>? keyValuePair)
        {
            keyValuePair = null;
            line = line.Trim();
            int index = line.IndexOf(delimiter);
            if (index < 1) return false;
            string key = line.Substring(0, index).Trim();
            string val = line.Substring(index + 1).Trim();
            keyValuePair = new KeyValuePair<string, string>(key, val);
            return true;
        }

        public static long ParseTime(Match match)
        {
            if (match.Success && match.Groups.Count == 4)
            {
                var h = Convert.ToInt32(match.Groups[1].Value, 10) * 3600;
                var m = Convert.ToInt32(match.Groups[2].Value, 10) * 60;
                var s = Convert.ToInt32(match.Groups[3].Value, 10);
                return h + m + s;
            }
            return -1;
        }

        public static int ParseIntSafe(string text) { return Int32.TryParse(text, out int n) ? n : 0; }
    }
}

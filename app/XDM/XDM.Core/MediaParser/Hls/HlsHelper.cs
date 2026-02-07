using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace XDM.Core.MediaParser.Hls
{
    public static class HlsHelper
    {
        public static Dictionary<string, string> ParseAttributes(string attrText)
        {
            var dict = new Dictionary<string, string>();
            var pairBuf = new StringBuilder();
            var insideQuote = false;
            foreach (var ch in attrText)
            {
                if (ch == ',' && !insideQuote)
                {
                    var keyValuePair = ParseKeyValuePair(pairBuf.ToString());
                    if (keyValuePair.HasValue)
                    {
                        dict[keyValuePair.Value.Key] = keyValuePair.Value.Value;
                    }
                    pairBuf = new StringBuilder();
                    continue;
                }
                if (ch == '"') insideQuote = !insideQuote;
                pairBuf.Append(ch);
            }
            if (pairBuf.Length > 0)
            {
                var keyValuePair = ParseKeyValuePair(pairBuf.ToString());
                if (keyValuePair.HasValue)
                {
                    dict[keyValuePair.Value.Key] = keyValuePair.Value.Value;
                }
            }
            return dict;
        }

        public static KeyValuePair<string,string>? ParseKeyValuePair(string pair)
        {
            var index = pair.IndexOf('=');
            if (index == -1) return null;
            return new KeyValuePair<string, string>(key: pair.Substring(0, index), value: pair.Substring(index + 1).Trim('"', '\'', ' '));
        }
    }
}

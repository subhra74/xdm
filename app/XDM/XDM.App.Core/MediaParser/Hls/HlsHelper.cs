using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace MediaParser.Hls
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
                        dict[keyValuePair.Value.key] = keyValuePair.Value.value;
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
                    dict[keyValuePair.Value.key] = keyValuePair.Value.value;
                }
            }
            return dict;
        }

        public static (string key, string value)? ParseKeyValuePair(string pair)
        {
            var index = pair.IndexOf('=');
            if (index == -1) return null;
            return (key: pair.Substring(0, index), value: pair.Substring(index + 1).Trim('"', '\'', ' '));
        }
    }
}

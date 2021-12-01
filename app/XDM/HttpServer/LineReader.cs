using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;

namespace HttpServer
{
    internal static class LineReader
    {
        internal static IEnumerable<string> ReadLines(Stream stream)
        {
            var buffer = new StringBuilder();
            while (true)
            {
                var x = stream.ReadByte();
                if (x == -1) throw new IOException("Unexpected EOF");
                if (x == '\n')
                {
                    if (buffer.Length == 0) yield break;
                    yield return buffer.ToString();
                }
                if (x != '\r') buffer.Append((char)x);
            }
        }
    }
}

using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using TraceLog;

namespace HttpServer
{
    internal static class LineReader
    {
        internal static IEnumerable<string> ReadLines(Stream stream)
        {
            //try
            //{
            // var lines = new List<string>();
            var buffer = new StringBuilder();
            while (true)
            {
                var x = stream.ReadByte();
                if (x == -1) throw new IOException("Unexpected EOF");
                if (x == '\n')
                {
                    if (buffer.Length == 0) yield break;
                    var line = buffer.ToString();
                    buffer = new StringBuilder();
                    yield return line;
                }
                else if (x != '\r') buffer.Append((char)x);
            }
            //return lines;
            //}
            //catch (Exception e)
            //{
            //    Log.Debug(e, "Error parsing headers");
            //    throw e;
            //}
        }
    }
}

using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

namespace NativeMessaging
{
    public static class IpcUtil
    {
        public static string MessageHeader = "---XDM-MESSAGE-START---";
        public static string MessageFooter = "---XDM-MESSAGE-END---";
        public static void Send(Stream socketOut, IEnumerable<string> args)
        {
            WriteLine(socketOut, MessageHeader);
            foreach (var arg in args)
            {
                var str = Convert.ToBase64String(Encoding.UTF8.GetBytes(arg));
                WriteLine(socketOut, str);
            }
            WriteLine(socketOut, MessageFooter);
            socketOut.Flush();
        }

        public static List<string> Receive(Stream socketIn)
        {
            var args = new List<string>();
            var header = ReadLine(socketIn);
            if (header == MessageHeader)
            {
                while (true)
                {
                    var line = ReadLine(socketIn);
                    if (line == MessageFooter)
                    {
                        return args;
                    }
                    var str = Convert.FromBase64String(line);
                    args.Add(Encoding.UTF8.GetString(str));
                }
            }
            throw new IOException("Invalid data");
        }

        private static string ReadLine(Stream stream)
        {
            var buf = new StringBuilder();
            while (true)
            {
                var x = stream.ReadByte();
                if (x == -1) throw new IOException("Unexpected EOF");
                if (x == '\n')
                {
                    return buf.ToString();
                }
                if (x != '\r')
                {
                    buf.Append((char)x);
                }
            }
        }

        private static void WriteLine(Stream stream, string text)
        {
            var bytes = Encoding.UTF8.GetBytes(text + "\n");
            stream.Write(bytes, 0, bytes.Length);
        }
    }
}

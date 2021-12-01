using System;
using System.Diagnostics;

namespace TraceLog
{
    public static class Log
    {
        public static void Debug(object obj, string message)
        {
            Trace.WriteLine(message + " : " + obj);
        }

        public static void Debug(string message)
        {
            Trace.WriteLine(message);
        }
    }
}

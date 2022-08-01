using System;
using System.Diagnostics;

namespace TraceLog
{
    public static class Log
    {
        public static void InitFileBasedTrace(string logfile)
        {
            try
            {
                Trace.WriteLine("Log init...");
                //Trace.Listeners.RemoveAt(0);
                Trace.Listeners.Add(new TextWriterTraceListener(logfile, "myListener"));
                Trace.AutoFlush = true;
                Trace.WriteLine("Log init...");
            }
            catch (Exception ex)
            {
                Trace.WriteLine(ex.ToString());
            }
        }

        public static void Debug(object obj, string message)
        {
            Trace.WriteLine($"[xdm-{DateTime.Now.ToLongTimeString()}] {message} : {obj}");
            //Trace.Flush();
        }

        public static void Debug(string message)
        {
            Trace.WriteLine($"[xdm-{DateTime.Now.ToLongTimeString()}] {message}");
            //Trace.Flush();
        }
    }
}

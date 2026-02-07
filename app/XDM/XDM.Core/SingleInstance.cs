using Newtonsoft.Json;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Text;
using System.Threading;
using TraceLog;
using XDM.Core.BrowserMonitoring;

namespace XDM.Core
{
    public static class SingleInstance
    {
        public static Mutex GlobalMutex;
        public static void Ensure()
        {
            try
            {
                using var mutex = Mutex.OpenExisting(@"Global\XDM_Active_Instance");
                throw new InstanceAlreadyRunningException(@"XDM instance already running, Mutex exists 'Global\XDM_Active_Instance'");
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Exception in NativeMessagingHostHandler ctor");
                if (ex is InstanceAlreadyRunningException)
                {
                    SendArgsToRunningInstance();
                    Environment.Exit(0);
                }
            }
            GlobalMutex = new Mutex(true, @"Global\XDM_Active_Instance");
        }

        private static void SendArgsToRunningInstance()
        {
            try
            {
                Log.Debug("Sending to running instance...");
                var args = Environment.GetCommandLineArgs().Skip(1);
                var request = WebRequest.Create("http://127.0.0.1:8597/args");
                var postData = JsonConvert.SerializeObject(args.Count() == 0 ? new string[] { "--restore-window" } : args);
                Log.Debug("Sending...");
                var data = Encoding.UTF8.GetBytes(postData);
                request.Method = "POST";
                request.ContentType = "application/json";
                request.ContentLength = data.Length;
                using (var stream = request.GetRequestStream())
                {
                    stream.Write(data, 0, data.Length);
                }
                var response = request.GetResponse();
                Log.Debug("Sent...");
            }
            catch (Exception ex)
            {
                Log.Debug(ex, "Failed sending args to running instance");
            }
        }
    }

    public class InstanceAlreadyRunningException : Exception
    {
        public InstanceAlreadyRunningException(string message) : base(message)
        {
        }
    }
}

using NativeMessaging;
using System;
using System.Collections.Generic;
using System.Linq;
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
                var ipcClient = new IpcClient();
                ipcClient.Connect(8597);
                Log.Debug("Receiving...");
                ipcClient!.Receive();
                Log.Debug("Sending...");
                //if no arguments, then restore ui of previously running process
                ipcClient.Send(args.Count() == 0 ? new string[] { "--restore-window" } : args);
                Log.Debug("Sent...");
                ipcClient.Close();
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

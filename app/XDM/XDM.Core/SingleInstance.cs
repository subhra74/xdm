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
                    var args = Environment.GetCommandLineArgs().Skip(1);
                    Log.Debug(ex, "Sending args to running instance");
                    //if no arguments, then restore ui of previously running process
                    IpcPipe.SendArgsToRunningInstance(args.Count() == 0 ? new string[] { "-r" } : args);
                    Environment.Exit(0);
                }
            }
            GlobalMutex = new Mutex(true, @"Global\XDM_Active_Instance");
        }
    }

    public class InstanceAlreadyRunningException : Exception
    {
        public InstanceAlreadyRunningException(string message) : base(message)
        {
        }
    }
}

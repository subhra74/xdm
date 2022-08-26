using System;
using TraceLog;
using XDM.Core;
using XDM.Core.Ipc;

namespace XDM.Core.BrowserMonitoring
{
    public static class BrowserMonitor
    {
        public static void Run()
        {
            var ipcServer = new IpcServer(8597);
            try
            {
                ipcServer.Start();
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
            //var pipe = new IpcPipe();
            //pipe.Run();
            //var http = new IpcHttpMessageProcessor();
            //http.Run();
        }
    }
}

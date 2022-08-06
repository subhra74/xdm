using XDM.Core;

namespace XDM.Core.BrowserMonitoring
{
    public static class BrowserMonitor
    {
        public static void Run()
        {
            var pipe = new IpcPipe();
            pipe.Run();
            var http = new IpcHttpMessageProcessor();
            http.Run();
        }
    }
}

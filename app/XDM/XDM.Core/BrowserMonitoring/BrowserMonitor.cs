using XDM.Core;

namespace XDM.Core.BrowserMonitoring
{
    public static class BrowserMonitor
    {
        public static void RunHttpIpcHandler(IApplicationCore app)
        {
            var handler = new IpcHttpHandler(app);
            handler.StartHttpIpcChannel();
        }

        public static NativeMessagingHostHandler RunNativeHostHandler(IApplicationCore app)
        {
            var handler = new NativeMessagingHostHandler(app);
            handler.StartPipedChannel();
            return handler;
        }
    }
}

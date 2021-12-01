using XDM.Core.Lib.Common;

namespace BrowserMonitoring
{
    public static class BrowserMonitor
    {
        public static void RunHttpIpcHandler(IApp app)
        {
            var handler = new IpcHttpHandler(app);
            handler.StartHttpIpcChannel();
        }

        public static NativeMessagingHostHandler RunNativeHostHandler(IApp app)
        {
            var handler = new NativeMessagingHostHandler(app);
            handler.StartPipedChannel();
            return handler;
        }
    }
}

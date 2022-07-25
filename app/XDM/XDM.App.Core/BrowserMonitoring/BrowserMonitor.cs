using XDM.Core;

namespace BrowserMonitoring
{
    public static class BrowserMonitor
    {
        public static void RunHttpIpcHandler(IAppService app)
        {
            var handler = new IpcHttpHandler(app);
            handler.StartHttpIpcChannel();
        }

        public static NativeMessagingHostHandler RunNativeHostHandler(IAppService app)
        {
            var handler = new NativeMessagingHostHandler(app);
            handler.StartPipedChannel();
            return handler;
        }
    }
}

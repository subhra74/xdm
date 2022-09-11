﻿using System;
using TraceLog;
using XDM.Core;
using XDM.Core.Ipc;

namespace XDM.Core.BrowserMonitoring
{
    public static class BrowserMonitor
    {
        private static IpcServer ipcServer;

        public static void Run()
        {
            ipcServer = new IpcServer(8597);
            try
            {
                ipcServer.Start();
                ApplicationContext.ApplicationEvent += ApplicationContext_ApplicationEvent;
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
        }

        private static void ApplicationContext_ApplicationEvent(object? sender, ApplicationEvent e)
        {
            if (e.EventType == "ConfigChanged")
            {
                ipcServer.SendConfig();
            }
        }
    }
}
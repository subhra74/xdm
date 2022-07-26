using System;
using System.Collections.Generic;
using System.Text;
using XDM.Core.BrowserMonitoring;
using XDM.Core.UI;

namespace XDM.Core
{
    public static class ApplicationContext
    {
        private static IApplicationCore? s_ApplicationCore;
        private static IApplication? s_IApplication;
        private static IApplicationWindow? s_ApplicationWindow;
        private static ILinkRefresher? s_LinkRefresher;
        private static IVideoTracker? s_VideoTracker;
        private static IClipboardMonitor? s_ClipboardMonitor;
        private static bool s_Init = false;

        public static event EventHandler? Initialized;
        public static event EventHandler<ApplicationEvent>? ApplicationEvent;

        public static IApplicationCore CoreService
        {
            get
            {
                if (!s_Init)
                {
                    throw new Exception("ApplicationContext is not initialized...");
                }
                return s_ApplicationCore!;
            }
        }

        public static IApplication Application
        {
            get
            {
                if (!s_Init)
                {
                    throw new Exception("ApplicationContext is not initialized...");
                }
                return s_IApplication!;
            }
        }

        public static IApplicationWindow MainWindow
        {
            get
            {
                if (!s_Init)
                {
                    throw new Exception("ApplicationContext is not initialized...");
                }
                return s_ApplicationWindow!;
            }
        }

        public static ILinkRefresher LinkRefresher
        {
            get
            {
                if (!s_Init)
                {
                    throw new Exception("ApplicationContext is not initialized...");
                }
                return s_LinkRefresher!;
            }
        }

        public static IVideoTracker VideoTracker
        {
            get
            {
                if (!s_Init)
                {
                    throw new Exception("ApplicationContext is not initialized...");
                }
                return s_VideoTracker!;
            }
        }

        public static IClipboardMonitor ClipboardMonitor
        {
            get
            {
                if (!s_Init)
                {
                    throw new Exception("ApplicationContext is not initialized...");
                }
                return s_ClipboardMonitor!;
            }
        }

        public static void BroadcastConfigChange()
        {
            ApplicationEvent?.Invoke(null, new ApplicationEvent("ConfigChanged"));
        }

        public static AppInstanceConfigurer Configurer()
        {
            return new AppInstanceConfigurer();
        }

        public class AppInstanceConfigurer
        {
            public AppInstanceConfigurer RegisterApplicationCore(IApplicationCore service)
            {
                s_ApplicationCore = service;
                return this;
            }

            public AppInstanceConfigurer RegisterApplication(IApplication service)
            {
                s_IApplication = service;
                return this;
            }

            public AppInstanceConfigurer RegisterApplicationWindow(IApplicationWindow service)
            {
                s_ApplicationWindow = service;
                return this;
            }

            public AppInstanceConfigurer RegisterLinkRefresher(ILinkRefresher service)
            {
                s_LinkRefresher = service;
                return this;
            }

            public AppInstanceConfigurer RegisterCapturedVideoTracker(IVideoTracker service)
            {
                s_VideoTracker = service;
                return this;
            }

            public AppInstanceConfigurer RegisterClipboardMonitor(IClipboardMonitor service)
            {
                s_ClipboardMonitor = service;
                return this;
            }

            public void Configure()
            {
                if (s_ApplicationCore == null || s_IApplication == null
                    || s_ApplicationWindow == null || s_LinkRefresher == null
                    || s_VideoTracker == null || s_ClipboardMonitor == null)
                {
                    throw new Exception("Please configure all dependecies");
                }
                s_Init = true;
                Initialized?.Invoke(null, EventArgs.Empty);
            }
        }
    }

    public class ApplicationEvent : EventArgs
    {
        public ApplicationEvent(string eventType, object? data = null)
        {
            EventType = eventType;
            Data = data;
        }

        public string EventType { get; }
        public object? Data { get; set; }

    }
}

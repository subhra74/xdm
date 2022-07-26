using System;
using System.Collections.Generic;
using System.Text;
using XDM.Core.UI;

namespace XDM.Core
{
    public static class ApplicationContext
    {
        private static IApplicationCore? s_ApplicationCore;
        private static IApplication? s_IApplication;
        private static IApplicationWindow? s_ApplicationWindow;
        private static bool s_Init = false;

        public static event EventHandler? Initialized;

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

        public static AppInstanceConfigurer Configurer()
        {
            return new AppInstanceConfigurer();
        }

        public class AppInstanceConfigurer
        {
            public AppInstanceConfigurer RegisterService(IApplicationCore service)
            {
                s_ApplicationCore = service;
                return this;
            }

            public AppInstanceConfigurer RegisterService(IApplication service)
            {
                s_IApplication = service;
                return this;
            }

            public AppInstanceConfigurer RegisterService(IApplicationWindow service)
            {
                s_ApplicationWindow = service;
                return this;
            }

            public void Configure()
            {
                if (s_ApplicationCore == null || s_IApplication == null || s_ApplicationWindow == null)
                {
                    throw new Exception("Please configure service, controller and other dependecies");
                }
                s_Init = true;
                Initialized?.Invoke(null, EventArgs.Empty);
            }
        }
    }
}

using System;
using System.Collections.Generic;
using System.Text;
using XDM.Core.UI;

namespace XDM.Core
{
    public static class AppInstance
    {
        private static IApplicationCore? s_ApplicationCore;
        private static IApplication? s_IApplication;
        private static IApplicationWindow? s_ApplicationWindow;

        private static bool s_Init = false;

        public static IApplicationCore ApplicationCore
        {
            get
            {
                if (!s_Init)
                {
                    throw new Exception("AppInstance is not initialized...");
                }
                return s_ApplicationCore!;
            }
        }

        public static IApplication IApplication
        {
            get
            {
                if (!s_Init)
                {
                    throw new Exception("AppInstance is not initialized...");
                }
                return s_IApplication!;
            }
        }

        public static IApplicationWindow MainView
        {
            get
            {
                if (!s_Init)
                {
                    throw new Exception("AppInstance is not initialized...");
                }
                return s_ApplicationWindow!;
            }
        }

        public class AppInstanceConfigurer
        {
            public AppInstanceConfigurer RegisterAppService(IApplicationCore service)
            {
                s_ApplicationCore = service;
                return this;
            }

            public AppInstanceConfigurer RegisterAppController(IApplication controller)
            {
                s_IApplication = controller;
                return this;
            }

            public AppInstanceConfigurer RegisterMainView(IApplicationWindow view)
            {
                s_ApplicationWindow = view;
                return this;
            }

            public void Configure()
            {
                if (s_ApplicationCore == null || s_IApplication == null)
                {
                    throw new Exception("Please configure service, controller and other dependecies");
                }
                s_Init = true;
            }
        }
    }
}

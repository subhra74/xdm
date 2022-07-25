using System;
using System.Collections.Generic;
using System.Text;
using XDM.Core.UI;

namespace XDM.Core
{
    public static class AppInstance
    {
        private static IAppService? s_AppService;
        private static IUIService? s_AppController;
        private static IMainView? s_MainView;

        private static bool s_Init = false;

        public static IAppService Service
        {
            get
            {
                if (!s_Init)
                {
                    throw new Exception("AppInstance is not initialized...");
                }
                return s_AppService!;
            }
        }

        public static IUIService UI
        {
            get
            {
                if (!s_Init)
                {
                    throw new Exception("AppInstance is not initialized...");
                }
                return s_AppController!;
            }
        }

        public static IMainView MainView
        {
            get
            {
                if (!s_Init)
                {
                    throw new Exception("AppInstance is not initialized...");
                }
                return s_MainView!;
            }
        }

        public class AppInstanceConfigurer
        {
            public AppInstanceConfigurer RegisterAppService(IAppService service)
            {
                s_AppService = service;
                return this;
            }

            public AppInstanceConfigurer RegisterAppController(IUIService controller)
            {
                s_AppController = controller;
                return this;
            }

            public AppInstanceConfigurer RegisterMainView(IMainView view)
            {
                s_MainView = view;
                return this;
            }

            public void Configure()
            {
                if (s_AppService == null || s_AppController == null)
                {
                    throw new Exception("Please configure service, controller and other dependecies");
                }
                s_Init = true;
            }
        }
    }
}

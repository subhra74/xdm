using System;
using System.Collections.Generic;
using System.Text;
using XDM.Core;

namespace XDM.App.Core.XDMApp
{
    public static class AppInstance
    {
        private static IAppService? s_AppService;
        private static IAppUIController? s_AppController;

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

        public static IAppUIController Controller
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

        public class AppInstanceConfigurer
        {
            public AppInstanceConfigurer RegisterAppService(IAppService service)
            {
                s_AppService = service;
                return this;
            }

            public AppInstanceConfigurer RegisterAppController(IAppUIController controller)
            {
                s_AppController = controller;
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

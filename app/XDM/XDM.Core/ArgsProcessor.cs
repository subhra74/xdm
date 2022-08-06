using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using XDM.Core;

namespace XDM.Core
{
    public static class ArgsProcessor
    {
        public static void Process(string[] commandArgs, int start = 0)
        {
            Dictionary<string, string?> args = ParseArgs(commandArgs, start);
            if (args.ContainsKey("-u"))
            {
                var url = args["-u"];
                if (!string.IsNullOrEmpty(url))
                {
                    ApplicationContext.CoreService.AddDownload(new Message { Url = url! });
                }
            }

            if (args.ContainsKey("-i"))
            {
                Config.Instance.RunOnLogon = true;
                Config.SaveConfig();
                ApplicationContext.Application.RunOnUiThread(() =>
                {
                    ApplicationContext.MainWindow.ShowAndActivate();
                    ApplicationContext.PlatformUIService.ShowBrowserMonitoringDialog();
                });
            }

            if (args.Count == 0|| args.ContainsKey("-r"))
            {
                ApplicationContext.Application.RunOnUiThread(() =>
                {
                    ApplicationContext.MainWindow.ShowAndActivate();
                });
            }
        }

        private static Dictionary<string, string?> ParseArgs(string[] args, int start = 0)
        {
            var options = new Dictionary<string, string?>();
            var key = string.Empty;
            for (int i = start; i < args.Length; i++)
            {
                var arg = args[i];
                if (key != string.Empty)
                {
                    options[key] = arg;
                    key = string.Empty;
                }
                else
                {
                    key = arg;
                    options[key] = null;
                }
            }
            return options;
        }
    }
}

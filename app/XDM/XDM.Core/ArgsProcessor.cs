using NativeMessaging;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using TraceLog;
using XDM.Core;
using XDM.Core.BrowserMonitoring;
using XDM.Core.Util;

namespace XDM.Core
{
    public static class ArgsProcessor
    {
        public static string[] SingleSwitches = new string[] { "--background", "--first-run", "--restore-window", "--quit", "--media", "--media-clear" };
        public static void Process(IEnumerable<string> commandArgs)
        {
            try
            {
                if (IsExtensionRegistration(commandArgs))
                {
                    RegisterExtension(commandArgs);
                    ApplicationContext.ExtensionRegistered();
                    return;
                }

                Dictionary<string, List<string>> args = ParseArgs(commandArgs);
                if (args.ContainsKey("--media-tab-url"))
                {
                    var url = args["--media-tab-url"][0];
                    var title = args["--media-tab-title"][0];
                    ApplicationContext.VideoTracker.UpdateMediaTitle(url, title);
                    return;
                }
                if (args.ContainsKey("--media-vid"))
                {
                    var vid = args["--media-vid"][0];
                    ApplicationContext.VideoTracker.AddVideoDownload(vid);
                    return;
                }
                if (args.ContainsKey("--media-clear"))
                {
                    ApplicationContext.VideoTracker.ClearVideoList();
                    return;
                }
                if (args.ContainsKey("--url") && args["--url"].Count == 1)
                {
                    var url = args["--url"][0];
                    var message = new Message();
                    message.Url = url;
                    message.RequestHeaders = new();
                    if (args.ContainsKey("-H"))
                    {
                        PopulateHeaders("-H", message, args);
                    }
                    if (args.ContainsKey("--header"))
                    {
                        PopulateHeaders("--header", message, args);
                    }
                    if (args.ContainsKey("--cookie"))
                    {
                        message.Cookies = args["--cookie"][0];
                    }
                    if (args.ContainsKey("--output"))
                    {
                        message.File = args["--output"][0];
                    }
                    if (args.ContainsKey("-o"))
                    {
                        message.File = args["-o"][0];
                    }
                    if (args.ContainsKey("-C"))
                    {
                        message.Cookies = args["-C"][0];
                    }
                    if (args.ContainsKey("--known-file-size"))
                    {
                        message.ResponseHeaders["Content-Length"] = args["--known-file-size"];
                    }
                    if (args.ContainsKey("--known-mime-type"))
                    {
                        message.ResponseHeaders["Content-Type"] = args["--known-mime-type"];
                    }
                    if (args.ContainsKey("--media-source-url"))
                    {
                        message.MediaSourceUrl = args["--media-source-url"][0];
                    }
                    if (args.ContainsKey("--tab-url"))
                    {
                        message.TabUrl = args["--tab-url"][0];
                    }

                    if (!args.ContainsKey("--media"))
                    {
                        ApplicationContext.CoreService.AddDownload(message);
                    }
                    else
                    {
                        VideoUrlHelper.ProcessMediaMessage(message);
                    }
                }

                if (args.ContainsKey("--quit"))
                {
                    Log.Debug("Received quit args, Exiting...");
                    Environment.Exit(0);
                }

                if (!args.ContainsKey("--background"))
                {
                    if (args.ContainsKey("--first-run"))
                    {
                        Config.Instance.RunOnLogon = true;
                        Config.SaveConfig();
                        ApplicationContext.Application.RunOnUiThread(() =>
                        {
                            ApplicationContext.MainWindow.ShowAndActivate();
                            if(!File.Exists(Path.Combine(Config.AppDir, "browser-integration-attempted")))
                            {
                                ApplicationContext.PlatformUIService.ShowBrowserMonitoringDialog();
                            }
                        });
                    }
                    else
                    {
                        ApplicationContext.Application.RunOnUiThread(() =>
                        {
                            ApplicationContext.MainWindow.ShowAndActivate();
                        });
                    }
                }
            }
            catch (Exception ex)
            {
                Log.Debug(ex, ex.Message);
            }
        }

        private static void PopulateHeaders(string headerArgName, Message message, Dictionary<string, List<string>> args)
        {
            var headers = args[headerArgName];
            foreach (var header in headers)
            {
                if (ParsingHelper.ParseKeyValuePair(header, ':', out var kv) && kv.HasValue)
                {
                    if (message.RequestHeaders.ContainsKey(kv.Value.Key))
                    {
                        message.RequestHeaders[kv.Value.Key].Add(kv.Value.Value);
                    }
                    else
                    {
                        message.RequestHeaders[kv.Value.Key] = new List<string> { kv.Value.Value };
                    }
                }
            }
        }

        private static Dictionary<string, List<string>> ParseArgs(IEnumerable<string> args)
        {
            var options = new Dictionary<string, List<string>>();
            var switchName = string.Empty;
            foreach (var arg in args)
            {
                if (arg.StartsWith("xdm+app://"))
                {
                    continue;
                }
                if (arg.StartsWith("-"))
                {
                    if (!options.ContainsKey(arg))
                    {
                        options[arg] = new List<string>();
                    }
                    if (SingleSwitches.Contains(arg))
                    {
                        switchName = string.Empty;
                    }
                    else
                    {
                        switchName = arg;
                    }
                }
                else
                {
                    if (switchName == string.Empty)
                    {
                        options["--url"] = new List<string> { arg };
                    }
                    else
                    {
                        options[switchName].Add(arg);
                        switchName = string.Empty;
                    }
                }
            }
            return options;
        }

        private static bool IsExtensionRegistration(IEnumerable<string> args)
        {
            if (args.Count() > 0)
            {
                return args.Where(arg => arg.StartsWith("xdm-app:chrome-extension://")).Count() == 1;
            }
            return false;
        }

        private static void RegisterExtension(IEnumerable<string> args)
        {
            ExtensionRegistrationHelper.AddExtension(args.Where(arg => arg.StartsWith("xdm-app:chrome-extension://")).First().Substring("xdm-app:".Length));
#if WINDOWS
            NativeMessagingHostConfigurer.InstallNativeMessagingHostForWindows(Browser.Chrome);
#elif LINUX
            NativeMessagingHostConfigurer.InstallNativeMessagingHostForLinux(Browser.Chrome);
#endif
        }
    }
}
